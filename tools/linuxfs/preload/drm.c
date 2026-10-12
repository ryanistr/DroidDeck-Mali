/*
 * GEM handle emulation for the KGSL device presented as a DRM render node.
 *
 * Compositors and Mesa validate or convert dma-bufs with PRIME ioctls on the render node; KGSL
 * has no GEM. Handles only serve as tokens to these callers, so a table of duplicated dma-buf
 * descriptors stands in for the kernel's, and every other device is left to libdrm.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <unistd.h>

#define MAX_HANDLES 4096

struct dma_heap_allocation_data {
  uint64_t len;
  uint32_t fd;
  uint32_t fd_flags;
  uint64_t heap_flags;
};
#define DMA_HEAP_IOC_MAGIC 'H'
#define DMA_HEAP_IOCTL_ALLOC _IOWR(DMA_HEAP_IOC_MAGIC, 0x0, struct dma_heap_allocation_data)

static int alloc_dma_buf(size_t len) {
  int heap_fd = open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC);
  int dma_fd = -1;
  if (heap_fd >= 0) {
    struct dma_heap_allocation_data data = {
      .len = len > 0 ? len : 4096,
      .fd = 0,
      .fd_flags = O_RDWR | O_CLOEXEC,
      .heap_flags = 0,
    };
    if (ioctl(heap_fd, DMA_HEAP_IOCTL_ALLOC, &data) == 0) {
      dma_fd = data.fd;
    }
    close(heap_fd);
  }
  if (dma_fd < 0) {
    dma_fd = memfd_create("dma-buf-fallback", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (dma_fd >= 0) {
      if (ftruncate(dma_fd, len > 0 ? len : 4096) != 0) {
        close(dma_fd);
        dma_fd = -1;
      }
    }
  }
  return dma_fd;
}

/* DRM_IOCTL_GEM_CLOSE from drm.h: _IOW('d', 0x09, struct drm_gem_close { u32 handle, pad; }). */
struct gem_close {
  uint32_t handle;
  uint32_t pad;
};
#define GEM_CLOSE _IOW('d', 0x09, struct gem_close)

struct handle_entry {
  uint32_t handle;
  int dma_fd;
  int drm_fd;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct handle_entry handle_entries[MAX_HANDLES];
static uint32_t next_handle = 1;
static dev_t kgsl_dev, mali_dev;
static int kgsl_state, mali_state; /* 0 unknown, 1 known, -1 absent */

static int is_non_drm_gpu(int fd) {
  struct stat st;
  pthread_mutex_lock(&lock);
  if (kgsl_state == 0) {
    kgsl_state = stat("/dev/kgsl-3d0", &st) == 0 ? 1 : -1;
    kgsl_dev = st.st_rdev;
  }
  if (mali_state == 0) {
    mali_state = stat("/dev/mali0", &st) == 0 ? 1 : -1;
    mali_dev = st.st_rdev;
  }
  pthread_mutex_unlock(&lock);
  if (fstat(fd, &st) != 0 || !S_ISCHR(st.st_mode)) return 0;
  return (kgsl_state > 0 && st.st_rdev == kgsl_dev) ||
         (mali_state > 0 && st.st_rdev == mali_dev);
}

static void *real(const char *name) {
  void *fn = dlsym(RTLD_NEXT, name);
  if (!fn) {
    errno = ENOSYS;
  }
  return fn;
}

/*
 * libdrm arrives as a dependency of whichever ICD the Vulkan loader dlopens, i.e. inside that
 * library's local scope, where dlsym(RTLD_NEXT) cannot see it. Resolving through a handle to
 * libdrm itself keeps the overrides below able to hand every real device back to libdrm instead
 * of free()ing businfo/deviceinfo, which libdrm owns as interior pointers of one allocation.
 */
static void *real_drm(const char *name) {
  void *fn = dlsym(RTLD_NEXT, name);
  if (fn) {
    return fn;
  }
  void *drm = dlopen("libdrm.so.2", RTLD_LAZY | RTLD_NOLOAD);
  if (!drm) {
    drm = dlopen("libdrm.so.2", RTLD_LAZY);
  }
  if (!drm) {
    for (int i = 2; i <= 4 && !drm; i++) {
      char soname[32];
      snprintf(soname, sizeof(soname), "libdrm.so.%d", i);
      drm = dlopen(soname, RTLD_LAZY | RTLD_NOLOAD);
    }
  }
  if (drm) {
    fn = dlsym(drm, name);
  }
  if (!fn) {
    errno = ENOSYS;
  }
  return fn;
}

static int same_file_description(int a, int b) {
  if (a == b) {
    return 1;
  }
#ifdef SYS_kcmp
  int saved_errno = errno;
  long ret = syscall(SYS_kcmp, getpid(), getpid(), 0 /* KCMP_FILE */, a, b);
  errno = saved_errno;
  return ret == 0;
#else
  return 0;
#endif
}

static int find_handle_locked(int fd, uint32_t handle) {
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == handle &&
        same_file_description(handle_entries[i].drm_fd, fd)) {
      return i;
    }
  }
  return -1;
}

/*
 * The old table used its slot index as the public handle. Once #275 started freeing slots,
 * rapidly recreated swapchains immediately reused those numbers. Keep storage slots reusable but
 * make the public token monotonic so a stale handle can fail instead of aliasing a newer buffer.
 */
static uint32_t alloc_handle_locked(void) {
  for (int tries = 0; tries <= MAX_HANDLES; tries++) {
    uint32_t candidate = next_handle++;
    if (next_handle == 0) {
      next_handle = 1;
    }
    if (candidate == 0) {
      continue;
    }
    int used = 0;
    for (int i = 0; i < MAX_HANDLES; i++) {
      if (handle_entries[i].handle == candidate) {
        used = 1;
        break;
      }
    }
    if (!used) {
      return candidate;
    }
  }
  return 0;
}

int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t *handle) {
  if (!is_non_drm_gpu(fd)) {
    int (*fn)(int, int, uint32_t *) = (int (*)(int, int, uint32_t *)) real_drm("drmPrimeFDToHandle");
    return fn ? fn(fd, prime_fd, handle) : -ENOSYS;
  }
  pthread_mutex_lock(&lock);
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle != 0 &&
        same_file_description(handle_entries[i].drm_fd, fd) &&
        same_file_description(handle_entries[i].dma_fd, prime_fd)) {
      *handle = handle_entries[i].handle;
      pthread_mutex_unlock(&lock);
      return 0;
    }
  }
  int dup_fd = fcntl(prime_fd, F_DUPFD_CLOEXEC, 0);
  if (dup_fd < 0) {
    pthread_mutex_unlock(&lock);
    return -errno;
  }
  int ret = -ENOMEM;
  for (int i = 0; i < MAX_HANDLES; i++) {
    if (handle_entries[i].handle == 0) {
      uint32_t token = alloc_handle_locked();
      if (!token) {
        break;
      }
      handle_entries[i].handle = token;
      handle_entries[i].dma_fd = dup_fd;
      handle_entries[i].drm_fd = fd;
      *handle = token;
      ret = 0;
      break;
    }
  }
  pthread_mutex_unlock(&lock);
  if (ret) {
    close(dup_fd);
  }
  return ret;
}

int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int *prime_fd) {
  if (!is_non_drm_gpu(fd)) {
    int (*fn)(int, uint32_t, uint32_t, int *) = (int (*)(int, uint32_t, uint32_t, int *)) real_drm("drmPrimeHandleToFD");
    return fn ? fn(fd, handle, flags, prime_fd) : -ENOSYS;
  }
  int ret = -EINVAL;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index < 0) {
    int dma_fd = alloc_dma_buf(16 * 1024 * 1024);
    if (dma_fd >= 0) {
      for (int i = 0; i < MAX_HANDLES; i++) {
        if (handle_entries[i].handle == 0) {
          handle_entries[i].handle = handle;
          handle_entries[i].dma_fd = dma_fd;
          handle_entries[i].drm_fd = fd;
          index = i;
          break;
        }
      }
      if (index < 0) {
        close(dma_fd);
      }
    }
  }
  if (index >= 0) {
    *prime_fd = fcntl(handle_entries[index].dma_fd, F_DUPFD_CLOEXEC, 0);
    ret = *prime_fd < 0 ? -errno : 0;
  }
  pthread_mutex_unlock(&lock);
  return ret;
}

/* Drops only a handle belonging to this non-DRM GPU file description. */
static int release_handle(int fd, uint32_t handle) {
  int found = 0;
  pthread_mutex_lock(&lock);
  int index = find_handle_locked(fd, handle);
  if (index >= 0) {
    close(handle_entries[index].dma_fd);
    handle_entries[index] = (struct handle_entry){0};
    found = 1;
  }
  pthread_mutex_unlock(&lock);
  return found;
}

int drmCloseBufferHandle(int fd, uint32_t handle) {
  if (!is_non_drm_gpu(fd)) {
    int (*fn)(int, uint32_t) = (int (*)(int, uint32_t)) real_drm("drmCloseBufferHandle");
    return fn ? fn(fd, handle) : -ENOSYS;
  }
  return release_handle(fd, handle) ? 0 : -EINVAL;
}

#define DRM_NODE_PRIMARY 0
#define DRM_NODE_CONTROL 1
#define DRM_NODE_RENDER  2
#define DRM_NODE_MAX     4
#define DRM_NODE_LEN     64

typedef struct _drmDevice {
  char **nodes;
  int available_nodes;
  int bustype;
  void *businfo;
  void *deviceinfo;
} *drmDevicePtr;

/*
 * Devices this library synthesized. libdrm allocates a real device and its businfo/deviceinfo in
 * one block and frees them itself, but alloc_fallback_drm_device() callocs each separately and
 * must free each. Tracking ours lets drmFreeDevice apply the right rule to both - freeing a real
 * device's interior pointers as if they were separate aborts with "free(): invalid pointer".
 */
static drmDevicePtr own_devices[64];
static size_t own_device_count;
static pthread_mutex_t own_lock = PTHREAD_MUTEX_INITIALIZER;

static void remember_own_device(drmDevicePtr d) {
  pthread_mutex_lock(&own_lock);
  if (own_device_count < sizeof(own_devices) / sizeof(own_devices[0])) {
    own_devices[own_device_count++] = d;
  }
  pthread_mutex_unlock(&own_lock);
}

static int is_own_device(drmDevicePtr d) {
  int found = 0;
  pthread_mutex_lock(&own_lock);
  for (size_t i = 0; i < own_device_count; i++) {
    if (own_devices[i] == d) {
      own_devices[i] = own_devices[--own_device_count];
      found = 1;
      break;
    }
  }
  pthread_mutex_unlock(&own_lock);
  return found;
}

static drmDevicePtr alloc_fallback_drm_device(const char *node_path) {
  drmDevicePtr dev = (drmDevicePtr) calloc(1, sizeof(struct _drmDevice));
  if (!dev) return NULL;
  dev->nodes = (char **) calloc(DRM_NODE_MAX, sizeof(char *));
  if (!dev->nodes) {
    free(dev);
    return NULL;
  }
  for (int i = 0; i < DRM_NODE_MAX; i++) {
    dev->nodes[i] = (char *) calloc(1, DRM_NODE_LEN);
  }
  strncpy(dev->nodes[DRM_NODE_PRIMARY], node_path, DRM_NODE_LEN - 1);
  strncpy(dev->nodes[DRM_NODE_RENDER], node_path, DRM_NODE_LEN - 1);
  dev->available_nodes = (1 << DRM_NODE_PRIMARY) | (1 << DRM_NODE_RENDER);
  dev->bustype = -1;
  remember_own_device(dev);
  return dev;
}

void drmFreeDevice(drmDevicePtr *device) {
  if (!device || !*device) return;
  drmDevicePtr d = *device;
  if (!is_own_device(d)) {
    /* libdrm's own device: it owns one allocation, so let its free do the work. */
    void (*fn)(drmDevicePtr *) = (void (*)(drmDevicePtr *)) real_drm("drmFreeDevice");
    if (fn) {
      fn(device);
      return;
    }
  }
  if (d->nodes) {
    for (int i = 0; i < DRM_NODE_MAX; i++) {
      free(d->nodes[i]);
    }
    free(d->nodes);
  }
  free(d->businfo);
  free(d->deviceinfo);
  free(d);
  *device = NULL;
}

void drmFreeDevices(drmDevicePtr devices[], int count) {
  if (!devices) return;
  for (int i = 0; i < count; i++) {
    if (devices[i]) {
      drmFreeDevice(&devices[i]);
    }
  }
}

static const char *get_fallback_node_path(void) {
  if (access("/dev/dri/renderD128", R_OK | W_OK) == 0) return "/dev/dri/renderD128";
  if (access("/dev/mali0", R_OK | W_OK) == 0) return "/dev/mali0";
  if (access("/dev/kgsl-3d0", R_OK | W_OK) == 0) return "/dev/kgsl-3d0";
  if (access("/dev/dri/card0", R_OK | W_OK) == 0) return "/dev/dri/card0";
  return "/dev/mali0";
}

int drmGetDeviceFromDevId(dev_t dev_id, uint32_t flags, drmDevicePtr *device) {
  int (*fn)(dev_t, uint32_t, drmDevicePtr *) = (int (*)(dev_t, uint32_t, drmDevicePtr *)) real_drm("drmGetDeviceFromDevId");
  int ret = fn ? fn(dev_id, flags, device) : -1;
  if (ret == 0 && device && *device) {
    return 0;
  }
  if (device) {
    *device = alloc_fallback_drm_device(get_fallback_node_path());
    if (*device) {
      return 0;
    }
  }
  return ret;
}

struct gbm_device;
struct gbm_device *gbm_create_device(int fd) {
  struct stat st;
  if (fstat(fd, &st) == 0 && S_ISCHR(st.st_mode)) {
    if (major(st.st_rdev) == 226 && minor(st.st_rdev) == 0) {
      return NULL;
    }
  }
  if (is_non_drm_gpu(fd)) {
    return NULL;
  }
  struct gbm_device *(*fn)(int) = (struct gbm_device *(*)(int)) real("gbm_create_device");
  return fn ? fn(fd) : NULL;
}



/*
 * Zink gives its handles back with the GEM_CLOSE ioctl itself, not drmCloseBufferHandle
 * (zink_bo.c, bo_destroy). KGSL refuses the ioctl, so the duplicated descriptor stayed open and
 * kept the whole buffer alive. Preserve #275's reclamation without letting another KGSL file
 * description release the token or making a new buffer immediately inherit a retired token.
 *
 * Caught at ioctl() (netif.c), which libdrm's drmIoctl calls, rather than by exporting drmIoctl:
 * every session process preloads this library, and exporting drmIoctl from it brought on a
 * judder replaying old frames in No Man's Sky on an Adreno 840 even with no handle released.
 */
__attribute__((visibility("hidden"))) int bl_drm_gem_close(int fd, unsigned long request, void *arg, int *rc) {
  if (request != GEM_CLOSE || !arg || !is_non_drm_gpu(fd)) {
    return 0;
  }
  if (release_handle(fd, ((struct gem_close *) arg)->handle)) {
    *rc = 0;
  } else {
    errno = EINVAL;
    *rc = -1;
  }
  return 1;
}

/*
 * fork() carries over only the calling thread, so a lock another thread was holding at that
 * instant stays held in the child by a thread that is not there to release it. Taking it before
 * the fork makes the copy consistent; the parent then unlocks it and the child, whose one thread
 * never locked it, gets a fresh one. (WinNative 79aa7f68.)
 */
static void drm_lock_before_fork(void) { pthread_mutex_lock(&lock); }
static void drm_unlock_after_fork(void) { pthread_mutex_unlock(&lock); }
static void drm_reset_after_fork(void) { pthread_mutex_init(&lock, NULL); }

__attribute__((constructor)) static void install_drm_fork_handlers(void) {
  pthread_atfork(drm_lock_before_fork, drm_unlock_after_fork, drm_reset_after_fork);
}
