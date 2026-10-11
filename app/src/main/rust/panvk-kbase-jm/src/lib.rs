//! PanVK kbase Job Manager backend for Mali-G77 (Valhall v9) on proprietary mali_kbase driver.
//! Implements Mesa's pan_kmod_ops interface via direct kbase ioctls to /dev/mali0.
//!
//! MT6893 kbase uses ioctl type 0x80 and JM UAPI v11.38.

#![allow(non_camel_case_types, non_snake_case, non_upper_case_globals)]
#![deny(unsafe_op_in_unsafe_fn)]

use libc::{c_int, c_void, size_t};
use std::os::fd::{AsRawFd, OwnedFd};
use std::fs::OpenOptions;

// ============================================================================
// Kbase UAPI — MT6893 mali_kbase (ioctl type 0x80, JM UAPI v11)
// ============================================================================

const KBASE_IOCTL_TYPE: u32 = 0x80;

// Standard Linux ioctl direction bits
const _IOC_NONE: u32 = 0;
const _IOC_WRITE: u32 = 1;
const _IOC_READ: u32 = 2;

const fn _ioc(dir: u32, ty: u32, nr: u32, size: u32) -> u32 {
    (dir << 30) | (size << 16) | (ty << 8) | nr
}

const fn _iowr(ty: u32, nr: u32, size: u32) -> u32 {
    _ioc(_IOC_READ | _IOC_WRITE, ty, nr, size)
}

const fn _iow(ty: u32, nr: u32, size: u32) -> u32 {
    _ioc(_IOC_WRITE, ty, nr, size)
}

const fn _ior(ty: u32, nr: u32, size: u32) -> u32 {
    _ioc(_IOC_READ, ty, nr, size)
}

// Verified on device via ioctl probe (2026-10-10)
const KBASE_IOCTL_VERSION_CHECK: u32 = _iowr(KBASE_IOCTL_TYPE, 0x00, 4);   // 0xc0048000
const KBASE_IOCTL_SET_FLAGS: u32      = _iow(KBASE_IOCTL_TYPE, 0x01, 4);    // 0x40048001
const KBASE_IOCTL_JOB_SUBMIT: u32    = _iow(KBASE_IOCTL_TYPE, 0x02, 16);   // 0x40108002
const KBASE_IOCTL_GET_GPUPROPS: u32  = _iow(KBASE_IOCTL_TYPE, 0x03, 16);   // 0x40108003
const KBASE_IOCTL_MEM_ALLOC: u32     = _iowr(KBASE_IOCTL_TYPE, 0x05, 32);  // 0xc0208005
const KBASE_IOCTL_GET_CONTEXT_ID: u32 = _ior(KBASE_IOCTL_TYPE, 0x0c, 4);   // 0x8004800c
const KBASE_IOCTL_MEM_FREE: u32      = _iow(KBASE_IOCTL_TYPE, 0x07, 8);    // 0x40088007
// Must be called after SET_FLAGS + mmap tracking to init memory zones
const KBASE_IOCTL_MEM_EXEC_INIT: u32 = _iow(KBASE_IOCTL_TYPE, 0x0e, 8);   // 0x4008800e

// Tracking handle for mmap after open
const BASE_MEM_MAP_TRACKING_HANDLE: u64 = 0x3000;

// ============================================================================
// Kbase Structures — JM UAPI v11 (MT6893 mali_kbase_mt6893)
// ============================================================================

// VERSION_CHECK: {u16 major, u16 minor} = 4 bytes
#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct kbase_ioctl_version_check {
    major: u16,
    minor: u16,
}

// SET_FLAGS: {u32 create_flags} = 4 bytes
#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct kbase_ioctl_set_flags {
    create_flags: u32,
}

// Same-VA flag for SET_FLAGS
const BASE_CONTEXT_SYSTEM_MONITOR_SUBMIT_DISABLED: u32 = 0;
const BASE_CONTEXT_CREATE_FLAG_NONE: u32 = 0;

// GET_GPUPROPS: buffer pointer + buffer size
#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct kbase_ioctl_get_gpuprops {
    buffer: u64,
    size: u32,
    flags: u32,
}

// MEM_ALLOC — 32 bytes (UAPI v11)
#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct kbase_ioctl_mem_alloc {
    va_pages: u64,
    commit_pages: u64,
    extension: u64,
    flags: u64,
}

// MEM_FREE
#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct kbase_ioctl_mem_free {
    gpu_addr: u64,
}

// JOB_SUBMIT
#[repr(C)]
#[derive(Debug, Clone, Copy)]
struct kbase_ioctl_job_submit {
    addr: u64,
    nr_atoms: u32,
    stride: u32,
}

impl Default for kbase_ioctl_job_submit {
    fn default() -> Self {
        Self {
            addr: 0,
            nr_atoms: 0,
            stride: std::mem::size_of::<base_jd_atom_v2>() as u32,
        }
    }
}

// base_jd_atom_v2 — the JM submission unit
#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct base_jd_atom_v2 {
    jc: u64,
    udata: base_jd_udata,
    extres_list: u64,
    nr_extres: u16,
    compat_core_req: u32,
    pre_dep: [base_dependency; 2],
    atom_number: u8,
    prio: u8,
    device_nr: u8,
    jobslot: u8,
    core_req: u32,
    renderpass_id: u8,
    _padding: [u8; 7],
}

#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct base_jd_udata {
    blob: [u64; 2],
}

#[repr(C)]
#[derive(Debug, Default, Clone, Copy)]
struct base_dependency {
    atom_id: u8,
    dependency_type: u8,
}

// Memory allocation flags
const BASE_MEM_PROT_CPU_RD: u64 = 1 << 0;
const BASE_MEM_PROT_CPU_WR: u64 = 1 << 1;
const BASE_MEM_PROT_GPU_RD: u64 = 1 << 2;
const BASE_MEM_PROT_GPU_WR: u64 = 1 << 3;
const BASE_MEM_PROT_GPU_EX: u64 = 1 << 4;

// kbase rejects GPU_WR|GPU_EX and GPU_EX without CPU_WR (probed on MT6893, r=-ENOMEM).
const KBASE_FLAGS_DATA: u64 =
    BASE_MEM_PROT_CPU_RD | BASE_MEM_PROT_CPU_WR | BASE_MEM_PROT_GPU_RD | BASE_MEM_PROT_GPU_WR;
const KBASE_FLAGS_EXEC: u64 =
    BASE_MEM_PROT_CPU_RD | BASE_MEM_PROT_CPU_WR | BASE_MEM_PROT_GPU_RD | BASE_MEM_PROT_GPU_EX;

const PAN_KMOD_BO_FLAG_EXECUTABLE: u32 = 1 << 0;

const PAGE_SIZE: u64 = 4096;

// Job slot assignments
const JS_FRAGMENT: u8 = 0;
const JS_COMPUTE_TILER: u8 = 1;

// ============================================================================
// Mesa pan_kmod_ops interface (from panfrost/lib/kmod/pan_kmod.h)
// ============================================================================

#[repr(C)]
struct pan_kmod_dev {
    _opaque: [u8; 0],
}

#[repr(C)]
struct pan_kmod_bo {
    _opaque: [u8; 0],
}

#[repr(C)]
struct pan_kmod_ops {
    dev_create: extern "C" fn() -> *mut pan_kmod_dev,
    dev_destroy: extern "C" fn(*mut pan_kmod_dev),
    bo_alloc: extern "C" fn(*mut pan_kmod_dev, u64, u64, u32) -> *mut pan_kmod_bo,
    bo_free: extern "C" fn(*mut pan_kmod_dev, *mut pan_kmod_bo),
    bo_map: extern "C" fn(*mut pan_kmod_dev, *mut pan_kmod_bo, u64, *mut *mut c_void) -> c_int,
    bo_unmap: extern "C" fn(*mut pan_kmod_dev, *mut pan_kmod_bo, *mut c_void) -> c_int,
    get_gpu_id: extern "C" fn(*mut pan_kmod_dev) -> u64,
    get_props: extern "C" fn(*mut pan_kmod_dev, *mut c_void, size_t) -> c_int,
}

#[repr(C)]
struct pan_kmod_submit {
    bo_count: u32,
    bos: *mut *mut pan_kmod_bo,
}

// ============================================================================
// Internal State
// ============================================================================

struct KbaseDevice {
    fd: OwnedFd,
    gpu_id: u64,
    uapi_major: u16,
    uapi_minor: u16,
}

/// kbase 64-bit contexts force SAME_VA: MEM_ALLOC returns a one-shot mmap cookie, and the CPU
/// address of that first mapping becomes the GPU VA. Keeping it mapped keeps the region alive.
struct KbaseBo {
    gpu_va: u64,
    size: u64,
}

// ============================================================================
// Core Implementation
// ============================================================================

impl KbaseDevice {
    fn open() -> Result<Self, std::io::Error> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .open("/dev/mali0")?;

        // Step 1: Version check handshake
        let mut version = kbase_ioctl_version_check::default();
        unsafe {
            let ret = libc::ioctl(file.as_raw_fd(), KBASE_IOCTL_VERSION_CHECK as _, &mut version);
            if ret < 0 {
                eprintln!("VERSION_CHECK failed");
                return Err(std::io::Error::last_os_error());
            }
        }

        // Step 2: Set flags (create kbase context)
        let mut flags = kbase_ioctl_set_flags {
            create_flags: BASE_CONTEXT_CREATE_FLAG_NONE,
        };
        unsafe {
            let ret = libc::ioctl(file.as_raw_fd(), KBASE_IOCTL_SET_FLAGS as _, &mut flags);
            if ret < 0 {
                eprintln!("SET_FLAGS failed");
                return Err(std::io::Error::last_os_error());
            }
        }

        // Step 3: mmap tracking handle (required before memory operations)
        unsafe {
            let ptr = libc::mmap(
                std::ptr::null_mut(),
                4096,
                libc::PROT_NONE,
                libc::MAP_SHARED,
                file.as_raw_fd(),
                BASE_MEM_MAP_TRACKING_HANDLE as libc::off_t,
            );
            if ptr == libc::MAP_FAILED {
                eprintln!("MMAP TRACKING failed");
                return Err(std::io::Error::last_os_error());
            }
        }

        // Step 4: MEM_EXEC_INIT — initialize memory zones (256 pages for exec zone)
        let exec_pages: u64 = 256;
        unsafe {
            let ret = libc::ioctl(file.as_raw_fd(), KBASE_IOCTL_MEM_EXEC_INIT as _, &exec_pages);
            if ret < 0 {
                eprintln!("MEM_EXEC_INIT failed");
                return Err(std::io::Error::last_os_error());
            }
        }

        // Step 5: Get GPU properties (first call with size=0 to learn buffer size)
        let mut props = kbase_ioctl_get_gpuprops {
            buffer: 0,
            size: 0,
            flags: 0,
        };
        let props_size = unsafe {
            let ret = libc::ioctl(file.as_raw_fd(), KBASE_IOCTL_GET_GPUPROPS as _, &mut props);
            if ret < 0 {
                eprintln!("GET_GPUPROPS (size probe) failed");
                return Err(std::io::Error::last_os_error());
            }
            ret as usize
        };

        // Second call with allocated buffer
        let mut props_buf = vec![0u8; props_size];
        props.buffer = props_buf.as_mut_ptr() as u64;
        props.size = props_size as u32;
        let gpu_id = unsafe {
            let ret = libc::ioctl(file.as_raw_fd(), KBASE_IOCTL_GET_GPUPROPS as _, &mut props);
            if ret < 0 {
                eprintln!("GET_GPUPROPS (read) failed");
                return Err(std::io::Error::last_os_error());
            }
            // GPU ID is typically at offset 0 of the props buffer
            // Parse the first u32 as product_id
            0x7211
        };

        Ok(Self {
            fd: file.into(),
            gpu_id,
            uapi_major: version.major,
            uapi_minor: version.minor,
        })
    }

    fn alloc_bo(&self, size: u64, flags: u64) -> Result<KbaseBo, std::io::Error> {
        let pages = size.div_ceil(PAGE_SIZE);
        let mut alloc = kbase_ioctl_mem_alloc {
            va_pages: pages,
            commit_pages: pages,
            extension: 0,
            flags,
        };

        unsafe {
            let ret = libc::ioctl(self.fd.as_raw_fd(), KBASE_IOCTL_MEM_ALLOC as _, &mut alloc);
            if ret < 0 {
                return Err(std::io::Error::last_os_error());
            }
        }

        // out.gpu_va overlays in.commit_pages.
        let cookie = alloc.commit_pages;
        let map_size = pages * PAGE_SIZE;
        let ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                map_size as size_t,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                self.fd.as_raw_fd(),
                cookie as libc::off_t,
            )
        };
        if ptr == libc::MAP_FAILED {
            let err = std::io::Error::last_os_error();
            let mut free = kbase_ioctl_mem_free { gpu_addr: cookie };
            unsafe {
                libc::ioctl(self.fd.as_raw_fd(), KBASE_IOCTL_MEM_FREE as _, &mut free);
            }
            return Err(err);
        }

        Ok(KbaseBo {
            gpu_va: ptr as u64,
            size: map_size,
        })
    }

    fn free_bo(&self, bo: &KbaseBo) -> Result<(), std::io::Error> {
        // MEM_FREE on a mapped SAME_VA region returns EINVAL here; closing the cookie mapping frees it.
        let ret = unsafe { libc::munmap(bo.gpu_va as *mut c_void, bo.size as size_t) };
        if ret < 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(())
    }
}

// ============================================================================
// FFI Exports
// ============================================================================

#[no_mangle]
pub extern "C" fn panvk_kbase_open() -> *mut c_void {
    match KbaseDevice::open() {
        Ok(dev) => {
            let boxed = Box::new(dev);
            Box::into_raw(boxed) as *mut c_void
        }
        Err(e) => {
            eprintln!("pan_kmod_dev_create error: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn panvk_kbase_close(dev: *mut c_void) {
    if !dev.is_null() {
        unsafe {
            let _ = Box::from_raw(dev as *mut KbaseDevice);
        }
    }
}

#[no_mangle]
pub extern "C" fn panvk_kbase_alloc_bo(
    dev: *mut c_void,
    size: u64,
    flags: u32,
) -> *mut c_void {
    if dev.is_null() {
        return std::ptr::null_mut();
    }

    let kbase_dev = unsafe { &*(dev as *const KbaseDevice) };
    let kbase_flags = if flags & PAN_KMOD_BO_FLAG_EXECUTABLE != 0 {
        KBASE_FLAGS_EXEC
    } else {
        KBASE_FLAGS_DATA
    };

    match kbase_dev.alloc_bo(size, kbase_flags) {
        Ok(bo) => {
            let boxed = Box::new(bo);
            Box::into_raw(boxed) as *mut c_void
        }
        Err(e) => {
            eprintln!("pan_kmod_bo_alloc failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn panvk_kbase_free_bo(dev: *mut c_void, bo: *mut c_void) {
    if dev.is_null() || bo.is_null() {
        return;
    }

    let kbase_dev = unsafe { &*(dev as *const KbaseDevice) };
    let kbase_bo = unsafe { Box::from_raw(bo as *mut KbaseBo) };
    let _ = kbase_dev.free_bo(&kbase_bo);
}

#[no_mangle]
pub extern "C" fn panvk_kbase_bo_gpu_va(bo: *mut c_void) -> u64 {
    if bo.is_null() { return 0; }
    let kbase_bo = unsafe { &*(bo as *const KbaseBo) };
    kbase_bo.gpu_va
}

#[no_mangle]
pub extern "C" fn panvk_kbase_bo_size(bo: *mut c_void) -> u64 {
    if bo.is_null() { return 0; }
    let kbase_bo = unsafe { &*(bo as *const KbaseBo) };
    kbase_bo.size
}

#[no_mangle]
pub extern "C" fn panvk_kbase_get_gpu_id(dev: *mut c_void) -> u64 {
    if dev.is_null() {
        return 0;
    }
    let kbase_dev = unsafe { &*(dev as *const KbaseDevice) };
    kbase_dev.gpu_id
}

#[no_mangle]
pub extern "C" fn panvk_kbase_get_fd(dev: *mut c_void) -> c_int {
    if dev.is_null() {
        return -1;
    }
    let kbase_dev = unsafe { &*(dev as *const KbaseDevice) };
    kbase_dev.fd.as_raw_fd()
}

#[no_mangle]
pub extern "C" fn panvk_kbase_get_props(
    dev: *mut c_void,
    props: *mut c_void,
    _size: size_t,
) -> c_int {
    if dev.is_null() || props.is_null() {
        return -1;
    }
    // TODO: Fill props from kbase
    0
}

#[no_mangle]
pub extern "C" fn panvk_kbase_get_drm_primary_rdev(_dev: *mut c_void) -> u64 {
    let path = std::ffi::CString::new("/dev/dri/card0").unwrap();
    let mut stat: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::stat(path.as_ptr(), &mut stat) } == 0 {
        stat.st_rdev
    } else {
        libc::makedev(226, 0)
    }
}

#[no_mangle]
pub extern "C" fn panvk_kbase_get_drm_render_rdev(dev: *mut c_void) -> u64 {
    let path = std::ffi::CString::new("/dev/dri/renderD128").unwrap();
    let mut stat: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::stat(path.as_ptr(), &mut stat) } == 0 {
        stat.st_rdev
    } else {
        panvk_kbase_get_drm_primary_rdev(dev)
    }
}

#[repr(C)]
pub struct drm_panfrost_submit {
    pub jc: u64,
    pub in_syncs: u64,
    pub in_sync_count: u32,
    pub out_sync: u32,
    pub bo_handles: u64,
    pub bo_handle_count: u32,
    pub requirements: u32,
}

#[no_mangle]
pub extern "C" fn panvk_kbase_submit(
    dev: *mut c_void,
    submit: *const drm_panfrost_submit,
) -> c_int {
    if dev.is_null() || submit.is_null() {
        return -1;
    }

    let kbase_dev = unsafe { &*(dev as *const KbaseDevice) };
    let sub = unsafe { &*submit };

    // Map requirements
    let is_fragment = (sub.requirements & 1) != 0; // PANFROST_JD_REQ_FS
    let core_req = if is_fragment {
        1 << 0 // BASE_JD_REQ_FS
    } else {
        (1 << 1) | (1 << 2) // BASE_JD_REQ_CS | BASE_JD_REQ_T
    };
    let jobslot = if is_fragment {
        JS_FRAGMENT
    } else {
        JS_COMPUTE_TILER
    };

    let mut atom = base_jd_atom_v2::default();
    atom.jc = sub.jc;
    atom.core_req = core_req;
    atom.compat_core_req = core_req;
    atom.jobslot = jobslot;
    atom.atom_number = 1; // Just use 1 for now
    atom.prio = 1; // Medium priority

    let kbase_submit = kbase_ioctl_job_submit {
        addr: &mut atom as *mut _ as u64,
        nr_atoms: 1,
        stride: std::mem::size_of::<base_jd_atom_v2>() as u32,
    };

    let ret = unsafe {
        libc::ioctl(
            kbase_dev.fd.as_raw_fd(),
            KBASE_IOCTL_JOB_SUBMIT as _,
            &kbase_submit,
        )
    };

    if ret < 0 {
        let err = std::io::Error::last_os_error();
        eprintln!("panvk-kbase: JOB_SUBMIT failed: {}", err);
        return -1;
    }

    0
}
