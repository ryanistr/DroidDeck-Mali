#!/usr/bin/env python3
"""Generate mesa-patches/0003-panvk-v9-jm.patch (Mesa 24.3.0 + v9 JM port).

V9 (Mali-G77, Valhall arch 9) reaches PanVK only via the JM (job-manager)
backend in Mesa 24.3.0, but the upstream vX/JM sources touch desc members
that only exist for PAN_ARCH <= 7 and reference a gfx.tsd that no struct
has, so a v9 build fails.  Following the proven Mesa 25.3 model for arch >= 9
(desc table via panvk_shader_desc_state, tsd in gfx state, dirty flags), this
patch threads v9 through the shared vX layer and the JM backend.

Run: python3 tools/panvk/gen-panvk-v9-jm.patch.py   (from repo root)
Requires: out/mesa-src = Mesa 24.3.0 source tree (sparse panfrost checkout).
"""

import sys
from difflib import unified_diff
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MESA = ROOT / "out" / "mesa-src"
OUT = ROOT / "tools" / "panvk" / "mesa-patches" / "0003-panvk-v9-jm.patch"

edits = {}  # fname -> [(old, new), ...]


def edit(fname, old, new):
    edits.setdefault(fname, []).append((old, new))


# --- 1. jm/panvk_cmd_buffer.h: add PAN_ARCH>=9 tsd to gfx state ----------
edit(
    "src/panfrost/vulkan/jm/panvk_cmd_buffer.h",
    """   struct panvk_rendering_state render;

   mali_ptr vpd;
   mali_ptr push_uniforms;
};
""",
    """   struct panvk_rendering_state render;

   mali_ptr vpd;
   mali_ptr push_uniforms;
   mali_ptr tsd;
};
""",
)

# --- 2. panvk_vX_shader.c: guard the desc memsets so v9 compiles ----------
edit(
    "src/panfrost/vulkan/panvk_vX_shader.c",
    """   case MESA_SHADER_COMPUTE:
      cmd->state.compute.shader = shader;
      memset(&cmd->state.compute.cs.desc, 0,
             sizeof(cmd->state.compute.cs.desc));
      break;
""",
    """   case MESA_SHADER_COMPUTE:
      cmd->state.compute.shader = shader;
#if PAN_ARCH <= 7
      memset(&cmd->state.compute.cs.desc, 0,
             sizeof(cmd->state.compute.cs.desc));
#endif
      break;
""",
)

edit(
    "src/panfrost/vulkan/panvk_vX_shader.c",
    """   case MESA_SHADER_VERTEX:
      cmd->state.gfx.vs.shader = shader;
      cmd->state.gfx.linked = false;
      memset(&cmd->state.gfx.vs.desc, 0, sizeof(cmd->state.gfx.vs.desc));
      break;
""",
    """   case MESA_SHADER_VERTEX:
      cmd->state.gfx.vs.shader = shader;
      cmd->state.gfx.linked = false;
#if PAN_ARCH <= 7
      memset(&cmd->state.gfx.vs.desc, 0, sizeof(cmd->state.gfx.vs.desc));
#endif
      break;
""",
)

edit(
    "src/panfrost/vulkan/panvk_vX_shader.c",
    """      cmd->state.gfx.linked = false;
#if PAN_ARCH <= 7
      cmd->state.gfx.fs.rsd = 0;
#endif
      memset(&cmd->state.gfx.fs.desc, 0, sizeof(cmd->state.gfx.fs.desc));
      break;
""",
    """      cmd->state.gfx.linked = false;
#if PAN_ARCH <= 7
      cmd->state.gfx.fs.rsd = 0;
      memset(&cmd->state.gfx.fs.desc, 0, sizeof(cmd->state.gfx.fs.desc));
#endif
      break;
""",
)

# --- 3. panvk_vX_cmd_desc_state.c: guard the memsets ----------------------
edit(
    "src/panfrost/vulkan/panvk_vX_cmd_desc_state.c",
    """      memset(&cmdbuf->state.gfx.vs.desc, 0, sizeof(cmdbuf->state.gfx.vs.desc));
      memset(&cmdbuf->state.gfx.fs.desc, 0, sizeof(cmdbuf->state.gfx.fs.desc));
   }

   if (pPushDescriptorSetInfo->stageFlags & VK_SHADER_STAGE_COMPUTE_BIT) {
      push_desc_set_write(cmdbuf, &cmdbuf->state.compute.desc_state,
                          pPushDescriptorSetInfo);

      memset(&cmdbuf->state.compute.cs.desc, 0,
             sizeof(cmdbuf->state.compute.cs.desc));
   }
""",
    """#if PAN_ARCH <= 7
      memset(&cmdbuf->state.gfx.vs.desc, 0, sizeof(cmdbuf->state.gfx.vs.desc));
      memset(&cmdbuf->state.gfx.fs.desc, 0, sizeof(cmdbuf->state.gfx.fs.desc));
#endif
   }

   if (pPushDescriptorSetInfo->stageFlags & VK_SHADER_STAGE_COMPUTE_BIT) {
      push_desc_set_write(cmdbuf, &cmdbuf->state.compute.desc_state,
                          pPushDescriptorSetInfo);

#if PAN_ARCH <= 7
      memset(&cmdbuf->state.compute.cs.desc, 0,
             sizeof(cmdbuf->state.compute.cs.desc));
#endif
   }
""",
)

# --- 4. panvk_vX_cmd_meta.c: keep the desc members but guard their reads so
#        v9 doesn't touch the PAN_ARCH<=7-only fields; also make compute_end
#        actually assign the save_ctx->cs.desc back. ----------------------
edit(
    "src/panfrost/vulkan/panvk_vX_cmd_meta.c",
    """   save_ctx->push_constants = cmdbuf->state.push_constants;
   save_ctx->cs.shader = cmdbuf->state.compute.shader;
   save_ctx->cs.desc = cmdbuf->state.compute.cs.desc;
}
""",
    """   save_ctx->push_constants = cmdbuf->state.push_constants;
   save_ctx->cs.shader = cmdbuf->state.compute.shader;
#if PAN_ARCH <= 7
   save_ctx->cs.desc = cmdbuf->state.compute.cs.desc;
#endif
}
""",
)

edit(
    "src/panfrost/vulkan/panvk_vX_cmd_meta.c",
    """   cmdbuf->state.compute.shader = save_ctx->cs.shader;
   cmdbuf->state.compute.cs.desc = save_ctx->cs.desc;

#if PAN_ARCH >= 9
   cmdbuf->state.compute.cs.desc.res_table = 0;
#endif
}
""",
    """   cmdbuf->state.compute.shader = save_ctx->cs.shader;
#if PAN_ARCH <= 7
   cmdbuf->state.compute.cs.desc = save_ctx->cs.desc;
#endif
}
""",
)

edit(
    "src/panfrost/vulkan/panvk_vX_cmd_meta.c",
    """   save_ctx->push_constants = cmdbuf->state.push_constants;
   save_ctx->fs.shader = cmdbuf->state.gfx.fs.shader;
   save_ctx->fs.desc = cmdbuf->state.gfx.fs.desc;
   save_ctx->vs.shader = cmdbuf->state.gfx.vs.shader;
   save_ctx->vs.desc = cmdbuf->state.gfx.vs.desc;
   save_ctx->vb0 = cmdbuf->state.gfx.vb.bufs[0];
""",
    """   save_ctx->push_constants = cmdbuf->state.push_constants;
   save_ctx->fs.shader = cmdbuf->state.gfx.fs.shader;
   save_ctx->vs.shader = cmdbuf->state.gfx.vs.shader;
#if PAN_ARCH <= 7
   save_ctx->fs.desc = cmdbuf->state.gfx.fs.desc;
   save_ctx->vs.desc = cmdbuf->state.gfx.vs.desc;
#endif
   save_ctx->vb0 = cmdbuf->state.gfx.vb.bufs[0];
""",
)

edit(
    "src/panfrost/vulkan/panvk_vX_cmd_meta.c",
    """   cmdbuf->state.gfx.fs.shader = save_ctx->fs.shader;
   cmdbuf->state.gfx.fs.desc = save_ctx->fs.desc;
   cmdbuf->state.gfx.vs.shader = save_ctx->vs.shader;
   cmdbuf->state.gfx.vs.desc = save_ctx->vs.desc;
   cmdbuf->state.gfx.vb.bufs[0] = save_ctx->vb0;

#if PAN_ARCH <= 7
   cmdbuf->state.gfx.vs.attribs = 0;
   cmdbuf->state.gfx.vs.attrib_bufs = 0;
   cmdbuf->state.gfx.fs.rsd = 0;
#else
   cmdbuf->state.gfx.fs.desc.res_table = 0;
   cmdbuf->state.gfx.vs.desc.res_table = 0;
#endif
""",
    """   cmdbuf->state.gfx.fs.shader = save_ctx->fs.shader;
   cmdbuf->state.gfx.vs.shader = save_ctx->vs.shader;
#if PAN_ARCH <= 7
   cmdbuf->state.gfx.fs.desc = save_ctx->fs.desc;
   cmdbuf->state.gfx.vs.desc = save_ctx->vs.desc;
#endif
   cmdbuf->state.gfx.vb.bufs[0] = save_ctx->vb0;

#if PAN_ARCH <= 7
   cmdbuf->state.gfx.vs.attribs = 0;
   cmdbuf->state.gfx.vs.attrib_bufs = 0;
   cmdbuf->state.gfx.fs.rsd = 0;
#endif
""",
)

# --- 5. panvk_vX_cmd_fb_preload.c: allocate & store TSD for v9 -------------
edit(
    "src/panfrost/vulkan/panvk_vX_cmd_fb_preload.c",
    """#if PAN_ARCH >= 9
   shader->spd = panvk_pool_alloc_desc(&dev->mempools.rw, SHADER_PROGRAM);
   if (!panvk_priv_mem_host_addr(shader->spd)) {
      vk_shader_destroy(&dev->vk, &shader->vk, NULL);
      return panvk_error(dev, VK_ERROR_OUT_OF_DEVICE_MEMORY);
   }

   pan_pack(panvk_priv_mem_host_addr(shader->spd), SHADER_PROGRAM, cfg) {
      cfg.stage = MALI_SHADER_STAGE_FRAGMENT;
      cfg.fragment_coverage_bitmask_type = MALI_COVERAGE_BITMASK_TYPE_GL;
      cfg.register_allocation = MALI_SHADER_REGISTER_ALLOCATION_32_PER_THREAD;
      cfg.binary = panvk_priv_mem_dev_addr(shader->code_mem);
      cfg.preload.r48_r63 = shader->info.preload >> 48;
   }
#endif
""",
    """#if PAN_ARCH >= 9
   shader->spd = panvk_pool_alloc_desc(&dev->mempools.rw, SHADER_PROGRAM);
   if (!panvk_priv_mem_host_addr(shader->spd)) {
      vk_shader_destroy(&dev->vk, &shader->vk, NULL);
      return panvk_error(dev, VK_ERROR_OUT_OF_DEVICE_MEMORY);
   }

   pan_pack(panvk_priv_mem_host_addr(shader->spd), SHADER_PROGRAM, cfg) {
      cfg.stage = MALI_SHADER_STAGE_FRAGMENT;
      cfg.fragment_coverage_bitmask_type = MALI_COVERAGE_BITMASK_TYPE_GL;
      cfg.register_allocation = MALI_SHADER_REGISTER_ALLOCATION_32_PER_THREAD;
      cfg.binary = panvk_priv_mem_dev_addr(shader->code_mem);
      cfg.preload.r48_r63 = shader->info.preload >> 48;
   }

   /* v9 JM has no CSF builder: keep the TSD in the gfx state and allocate it
    * lazily from the current batch. */
   struct panvk_batch *batch = cmdbuf->cur_batch;
   if (!cmdbuf->state.gfx.tsd) {
      if (!batch || !batch->tls.gpu)
         batch->tls = panvk_cmd_alloc_desc(cmdbuf, LOCAL_STORAGE);
      if (!batch || !batch->tls.gpu)
         return VK_ERROR_OUT_OF_DEVICE_MEMORY;
      cmdbuf->state.gfx.tsd = batch->tls.gpu;
   }
#endif
""",
)


def emit():
    if not (MESA / "src" / "panfrost").is_dir():
        sys.exit(f"{MESA} is not a Mesa source tree (need src/panfrost)")

    out = []
    for fname, es in edits.items():
        path = MESA / fname
        text = path.read_text()
        new_full = text
        for old, new in es:
            if new_full.count(old) != 1:
                sys.exit(f"{fname}: block not unique/absent: {old[:60]!r}")
            new_full = new_full.replace(old, new)

        diff = list(
            unified_diff(
                text.splitlines(keepends=True),
                new_full.splitlines(keepends=True),
            )
        )
        out.append(f"diff --git a/{fname} b/{fname}\n")
        out.append(f"--- a/{fname}\n")
        out.append(f"+++ b/{fname}\n")
        out.extend(diff[2:])  # drop unified_diff's own ---/+++ headers

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(out))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(edits)} files)")


if __name__ == "__main__":
    emit()