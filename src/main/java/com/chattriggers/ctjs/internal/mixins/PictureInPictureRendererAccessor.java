//? if >=26.3 {
/*package com.chattriggers.ctjs.internal.mixins;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PictureInPictureRenderer.class)
public interface PictureInPictureRendererAccessor {
    @Accessor("texture")
    GpuTexture ctjs$getTexture();
}
*///?}
