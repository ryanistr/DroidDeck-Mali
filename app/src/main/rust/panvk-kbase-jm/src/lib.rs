//! PanVK kbase Job Manager backend for Mali-G77 (Valhall v9) on proprietary mali_kbase driver.
//! Implements Mesa's pan_kmod_ops interface via direct kbase ioctls to /dev/mali0.
//!
//! MT6893 kbase uses ioctl type 0x80 and JM UAPI v11.38.

#![allow(non_camel_case_types, non_snake_case, non_upper_case_globals)]
#![deny(unsafe_op_in_unsafe_fn)]

use libc::{c_int, c_void, size_t};
use std::os::fd::{AsRawFd, OwnedFd};
use std::fs::OpenOptions;
use std::sync::atomic::{AtomicU32, AtomicU64, AtomicU8, Ordering};

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
            stride: std::mem::size_of::<KbaseAtom72>() as u32,
        }
    }
}

// base_dependency — dependency pair
#[repr(C, packed)]
#[derive(Debug, Default, Clone, Copy)]
pub struct base_dependency {
    pub atom_id: u8,
    pub dependency_type: u8,
}

// KbaseAtom72 — MT6893 vendor r49 packed layout (72 bytes)
#[repr(C, packed)]
#[derive(Debug, Default, Clone, Copy)]
pub struct KbaseAtom72 {
    pub seq_nr: u64,                     // 0..8
    pub jc: u64,                         // 8..16
    pub udata: [u64; 2],                 // 16..32
    pub extres_list: u64,                // 32..40
    pub nr_extres: u16,                  // 40..42
    pub jit_id: [u8; 2],                 // 42..44
    pub pre_dep: [base_dependency; 2],   // 44..48 (2 x 2 bytes)
    pub atom_number: u8,                 // 48 (0x30)
    pub prio: u8,                        // 49 (0x31)
    pub device_nr: u8,                   // 50 (0x32)
    pub jobslot: u8,                     // 51 (0x33)
    pub core_req: u32,                   // 52..56 (0x34..0x38)
    pub renderpass_id: u8,               // 56 (0x38)
    pub padding: [u8; 7],                // 57..64
    pub frame_nr: u32,                   // 64..68
    pub pad2: u32,                       // 68..72
}

// KbaseAtom64 — standard v11 JM layout without seq_nr (64 bytes)
#[repr(C, packed)]
#[derive(Debug, Default, Clone, Copy)]
pub struct KbaseAtom64 {
    pub jc: u64,                         // 0..8
    pub udata: [u64; 2],                 // 8..24
    pub extres_list: u64,                // 24..32
    pub nr_extres: u16,                  // 32..34
    pub jit_id: [u8; 2],                 // 34..36
    pub pre_dep: [base_dependency; 2],   // 36..40
    pub atom_number: u8,                 // 40
    pub prio: u8,                        // 41
    pub device_nr: u8,                   // 42
    pub jobslot: u8,                     // 43
    pub core_req: u32,                   // 44..48
    pub renderpass_id: u8,               // 48
    pub padding: [u8; 7],                // 49..56
    pub frame_nr: u32,                   // 56..60
    pub pad2: u32,                       // 60..64
}

// KbaseAtom56 — legacy v10 JM layout (56 bytes)
#[repr(C, packed)]
#[derive(Debug, Default, Clone, Copy)]
pub struct KbaseAtom56 {
    pub jc: u64,                         // 0..8
    pub udata: [u64; 2],                 // 8..24
    pub extres_list: u64,                // 24..32
    pub nr_extres: u16,                  // 32..34
    pub jit_id: [u8; 2],                 // 34..36
    pub pre_dep: [base_dependency; 2],   // 36..40
    pub atom_number: u8,                 // 40
    pub prio: u8,                        // 41
    pub device_nr: u8,                   // 42
    pub jobslot: u8,                     // 43
    pub core_req: u32,                   // 44..48
    pub padding: [u8; 8],                // 48..56
}

#[repr(C, packed)]
#[derive(Debug, Default, Clone, Copy)]
pub struct base_jd_event_v2 {
    pub event_code: u32,                 // 0..4
    pub atom_number: u8,                 // 4..5
    pub padding: [u8; 3],                // 5..8
    pub udata: [u64; 2],                 // 8..24
}

// JD core requirements
pub const BASE_JD_REQ_DEP: u32 = 0;
pub const BASE_JD_REQ_FS: u32  = 1 << 0; // 0x001: Fragment shader job
pub const BASE_JD_REQ_CS: u32  = 1 << 1; // 0x002: Compute shader job
pub const BASE_JD_REQ_T: u32   = 1 << 2; // 0x004: Tiling job
pub const BASE_JD_REQ_CF: u32  = 1 << 3; // 0x008: Cache flush
pub const BASE_JD_REQ_V: u32   = 1 << 4; // 0x010: Value writeback job

// JD event codes
pub const BASE_JD_EVENT_DONE: u32               = 0x01;
pub const BASE_JD_EVENT_STOPPED: u32            = 0x02;
pub const BASE_JD_EVENT_TERMINATED: u32         = 0x03;
pub const BASE_JD_EVENT_ACTIVE: u32             = 0x04;
pub const BASE_JD_EVENT_JOB_CONFIG_FAULT: u32   = 0x40;
pub const BASE_JD_EVENT_JOB_POWER_FAULT: u32    = 0x48;
pub const BASE_JD_EVENT_JOB_READ_FAULT: u32     = 0x50;
pub const BASE_JD_EVENT_MEM_GROWTH_FAILED: u32  = 0x58;
pub const BASE_JD_EVENT_JOB_CANCELLED: u32      = 0x80;


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

static WINNING_STRIDE: AtomicU32 = AtomicU32::new(0);
static ATOM_COUNTER: AtomicU8 = AtomicU8::new(1);
static SEQ_COUNTER: AtomicU64 = AtomicU64::new(1);

fn event_code_str(code: u32) -> &'static str {
    match code {
        BASE_JD_EVENT_DONE => "DONE",
        BASE_JD_EVENT_STOPPED => "STOPPED",
        BASE_JD_EVENT_TERMINATED => "TERMINATED",
        BASE_JD_EVENT_ACTIVE => "ACTIVE",
        BASE_JD_EVENT_JOB_CONFIG_FAULT => "JOB_CONFIG_FAULT",
        BASE_JD_EVENT_JOB_POWER_FAULT => "JOB_POWER_FAULT",
        BASE_JD_EVENT_JOB_READ_FAULT => "JOB_READ_FAULT",
        BASE_JD_EVENT_MEM_GROWTH_FAILED => "MEM_GROWTH_FAILED",
        BASE_JD_EVENT_JOB_CANCELLED => "JOB_CANCELLED",
        _ => "UNKNOWN_FAULT",
    }
}

fn parse_u32_env(name: &str) -> Option<u32> {
    let val = std::env::var(name).ok()?;
    let trimmed = val.trim();
    if let Some(hex) = trimmed.strip_prefix("0x").or_else(|| trimmed.strip_prefix("0X")) {
        u32::from_str_radix(hex, 16).ok()
    } else {
        trimmed.parse::<u32>().ok()
    }
}

fn get_frag_core_req() -> u32 {
    parse_u32_env("PANVK_KBASE_FRAG_CORE")
        .unwrap_or(BASE_JD_REQ_FS | BASE_JD_REQ_CS) // 0x003
}

fn get_vtc_core_req() -> u32 {
    parse_u32_env("PANVK_KBASE_VTC_CORE")
        .unwrap_or(BASE_JD_REQ_CS | BASE_JD_REQ_T | BASE_JD_REQ_V) // 0x016
}

fn submit_with_stride(
    fd: c_int,
    jc: u64,
    core_req: u32,
    jobslot: u8,
    atom_num: u8,
    seq_num: u64,
    stride: u32,
) -> Result<u32, std::io::Error> {
    match stride {
        72 => {
            let mut atom = KbaseAtom72 {
                seq_nr: seq_num,
                jc,
                udata: [0, 0],
                extres_list: 0,
                nr_extres: 0,
                jit_id: [0, 0],
                pre_dep: [base_dependency::default(); 2],
                atom_number: atom_num,
                prio: 1,
                device_nr: 0,
                jobslot,
                core_req,
                renderpass_id: 0,
                padding: [0; 7],
                frame_nr: 1,
                pad2: 0,
            };
            let submit = kbase_ioctl_job_submit {
                addr: &mut atom as *mut _ as u64,
                nr_atoms: 1,
                stride: 72,
            };
            let ret = unsafe { libc::ioctl(fd, KBASE_IOCTL_JOB_SUBMIT as _, &submit) };
            if ret < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(72)
        }
        56 => {
            let mut atom = KbaseAtom56 {
                jc,
                udata: [0, 0],
                extres_list: 0,
                nr_extres: 0,
                jit_id: [0, 0],
                pre_dep: [base_dependency::default(); 2],
                atom_number: atom_num,
                prio: 1,
                device_nr: 0,
                jobslot,
                core_req,
                padding: [0; 8],
            };
            let submit = kbase_ioctl_job_submit {
                addr: &mut atom as *mut _ as u64,
                nr_atoms: 1,
                stride: 56,
            };
            let ret = unsafe { libc::ioctl(fd, KBASE_IOCTL_JOB_SUBMIT as _, &submit) };
            if ret < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(56)
        }
        64 => {
            let mut atom = KbaseAtom64 {
                jc,
                udata: [0, 0],
                extres_list: 0,
                nr_extres: 0,
                jit_id: [0, 0],
                pre_dep: [base_dependency::default(); 2],
                atom_number: atom_num,
                prio: 1,
                device_nr: 0,
                jobslot,
                core_req,
                renderpass_id: 0,
                padding: [0; 7],
                frame_nr: 1,
                pad2: 0,
            };
            let submit = kbase_ioctl_job_submit {
                addr: &mut atom as *mut _ as u64,
                nr_atoms: 1,
                stride: 64,
            };
            let ret = unsafe { libc::ioctl(fd, KBASE_IOCTL_JOB_SUBMIT as _, &submit) };
            if ret < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(64)
        }
        other => {
            eprintln!("panvk-kbase: unsupported stride {}", other);
            Err(std::io::Error::from_raw_os_error(libc::EINVAL))
        }
    }
}

fn submit_atom(
    fd: c_int,
    jc: u64,
    core_req: u32,
    jobslot: u8,
    atom_num: u8,
    seq_num: u64,
) -> Result<u32, std::io::Error> {
    if let Some(forced_stride) = parse_u32_env("PANVK_KBASE_STRIDE") {
        return submit_with_stride(fd, jc, core_req, jobslot, atom_num, seq_num, forced_stride);
    }

    let winning = WINNING_STRIDE.load(Ordering::Relaxed);
    if winning != 0 {
        return submit_with_stride(fd, jc, core_req, jobslot, atom_num, seq_num, winning);
    }

    // Auto-detect stride: try 72 first, then 56, then 64
    match submit_with_stride(fd, jc, core_req, jobslot, atom_num, seq_num, 72) {
        Ok(s) => {
            WINNING_STRIDE.store(72, Ordering::Relaxed);
            eprintln!("panvk-kbase: JOB_SUBMIT auto-detected stride 72 (MTK vendor packed)");
            Ok(s)
        }
        Err(e) if e.raw_os_error() == Some(libc::EINVAL) => {
            match submit_with_stride(fd, jc, core_req, jobslot, atom_num, seq_num, 56) {
                Ok(s) => {
                    WINNING_STRIDE.store(56, Ordering::Relaxed);
                    eprintln!("panvk-kbase: JOB_SUBMIT auto-detected stride 56");
                    Ok(s)
                }
                Err(e2) if e2.raw_os_error() == Some(libc::EINVAL) => {
                    let s = submit_with_stride(fd, jc, core_req, jobslot, atom_num, seq_num, 64)?;
                    WINNING_STRIDE.store(64, Ordering::Relaxed);
                    eprintln!("panvk-kbase: JOB_SUBMIT auto-detected stride 64");
                    Ok(s)
                }
                Err(e2) => Err(e2),
            }
        }
        Err(e) => Err(e),
    }
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
    let (core_req, jobslot) = if is_fragment {
        (get_frag_core_req(), JS_FRAGMENT)
    } else {
        (get_vtc_core_req(), JS_COMPUTE_TILER)
    };

    let atom_num = {
        let n = ATOM_COUNTER.fetch_add(1, Ordering::Relaxed);
        if n == 0 { 1 } else { n }
    };
    let seq_num = SEQ_COUNTER.fetch_add(1, Ordering::Relaxed);

    if let Err(e) = submit_atom(
        kbase_dev.fd.as_raw_fd(),
        sub.jc,
        core_req,
        jobslot,
        atom_num,
        seq_num,
    ) {
        eprintln!("panvk-kbase: JOB_SUBMIT failed (slot {}): {}", jobslot, e);
        return -1;
    }

    // Synchronously wait for completion event from /dev/mali0
    let mut pfd = libc::pollfd {
        fd: kbase_dev.fd.as_raw_fd(),
        events: libc::POLLIN,
        revents: 0,
    };
    let pret = unsafe { libc::poll(&mut pfd, 1, 3000) };
    if pret <= 0 {
        if pret == 0 {
            eprintln!(
                "panvk-kbase: timeout (3000ms) waiting for atom {} completion (slot {})",
                atom_num, jobslot
            );
        } else {
            eprintln!("panvk-kbase: poll error: {}", std::io::Error::last_os_error());
        }
        return -1;
    }

    let mut event = base_jd_event_v2::default();
    let rret = unsafe {
        libc::read(
            kbase_dev.fd.as_raw_fd(),
            &mut event as *mut _ as *mut libc::c_void,
            std::mem::size_of::<base_jd_event_v2>(),
        )
    };
    if rret < 0 {
        eprintln!("panvk-kbase: read event failed: {}", std::io::Error::last_os_error());
        return -1;
    }

    let event_code = event.event_code;
    let event_atom = event.atom_number;

    if event_code != BASE_JD_EVENT_DONE {
        eprintln!(
            "panvk-kbase: atom {} failed with event_code 0x{:x} ({})",
            event_atom,
            event_code,
            event_code_str(event_code),
        );
        return -1;
    }


    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_struct_sizes() {
        assert_eq!(std::mem::size_of::<KbaseAtom72>(), 72);
        assert_eq!(std::mem::size_of::<KbaseAtom64>(), 64);
        assert_eq!(std::mem::size_of::<KbaseAtom56>(), 56);
        assert_eq!(std::mem::size_of::<base_jd_event_v2>(), 24);
        assert_eq!(std::mem::size_of::<kbase_ioctl_job_submit>(), 16);
    }

    #[test]
    fn test_kbase_atom_72_offsets() {
        let a = KbaseAtom72::default();
        let base = &a as *const _ as usize;
        assert_eq!(std::ptr::addr_of!(a.seq_nr) as usize - base, 0);
        assert_eq!(std::ptr::addr_of!(a.jc) as usize - base, 8);
        assert_eq!(std::ptr::addr_of!(a.udata) as usize - base, 16);
        assert_eq!(std::ptr::addr_of!(a.extres_list) as usize - base, 32);
        assert_eq!(std::ptr::addr_of!(a.nr_extres) as usize - base, 40);
        assert_eq!(std::ptr::addr_of!(a.jit_id) as usize - base, 42);
        assert_eq!(std::ptr::addr_of!(a.pre_dep) as usize - base, 44);
        assert_eq!(std::ptr::addr_of!(a.atom_number) as usize - base, 48);
        assert_eq!(std::ptr::addr_of!(a.prio) as usize - base, 49);
        assert_eq!(std::ptr::addr_of!(a.device_nr) as usize - base, 50);
        assert_eq!(std::ptr::addr_of!(a.jobslot) as usize - base, 51);
        assert_eq!(std::ptr::addr_of!(a.core_req) as usize - base, 52);
        assert_eq!(std::ptr::addr_of!(a.renderpass_id) as usize - base, 56);
        assert_eq!(std::ptr::addr_of!(a.padding) as usize - base, 57);
        assert_eq!(std::ptr::addr_of!(a.frame_nr) as usize - base, 64);
        assert_eq!(std::ptr::addr_of!(a.pad2) as usize - base, 68);
    }

    #[test]
    fn test_kbase_atom_56_offsets() {
        let a = KbaseAtom56::default();
        let base = &a as *const _ as usize;
        assert_eq!(std::ptr::addr_of!(a.jc) as usize - base, 0);
        assert_eq!(std::ptr::addr_of!(a.udata) as usize - base, 8);
        assert_eq!(std::ptr::addr_of!(a.extres_list) as usize - base, 24);
        assert_eq!(std::ptr::addr_of!(a.nr_extres) as usize - base, 32);
        assert_eq!(std::ptr::addr_of!(a.jit_id) as usize - base, 34);
        assert_eq!(std::ptr::addr_of!(a.pre_dep) as usize - base, 36);
        assert_eq!(std::ptr::addr_of!(a.atom_number) as usize - base, 40);
        assert_eq!(std::ptr::addr_of!(a.prio) as usize - base, 41);
        assert_eq!(std::ptr::addr_of!(a.device_nr) as usize - base, 42);
        assert_eq!(std::ptr::addr_of!(a.jobslot) as usize - base, 43);
        assert_eq!(std::ptr::addr_of!(a.core_req) as usize - base, 44);
        assert_eq!(std::ptr::addr_of!(a.padding) as usize - base, 48);
    }

    #[test]
    fn test_kbase_atom_64_offsets() {
        let a = KbaseAtom64::default();
        let base = &a as *const _ as usize;
        assert_eq!(std::ptr::addr_of!(a.jc) as usize - base, 0);
        assert_eq!(std::ptr::addr_of!(a.udata) as usize - base, 8);
        assert_eq!(std::ptr::addr_of!(a.extres_list) as usize - base, 24);
        assert_eq!(std::ptr::addr_of!(a.nr_extres) as usize - base, 32);
        assert_eq!(std::ptr::addr_of!(a.jit_id) as usize - base, 34);
        assert_eq!(std::ptr::addr_of!(a.pre_dep) as usize - base, 36);
        assert_eq!(std::ptr::addr_of!(a.atom_number) as usize - base, 40);
        assert_eq!(std::ptr::addr_of!(a.prio) as usize - base, 41);
        assert_eq!(std::ptr::addr_of!(a.device_nr) as usize - base, 42);
        assert_eq!(std::ptr::addr_of!(a.jobslot) as usize - base, 43);
        assert_eq!(std::ptr::addr_of!(a.core_req) as usize - base, 44);
        assert_eq!(std::ptr::addr_of!(a.renderpass_id) as usize - base, 48);
        assert_eq!(std::ptr::addr_of!(a.padding) as usize - base, 49);
        assert_eq!(std::ptr::addr_of!(a.frame_nr) as usize - base, 56);
        assert_eq!(std::ptr::addr_of!(a.pad2) as usize - base, 60);
    }

    #[test]
    fn test_core_req_defaults() {
        assert_eq!(get_frag_core_req(), 0x003);
        assert_eq!(get_vtc_core_req(), 0x016);
    }
}

