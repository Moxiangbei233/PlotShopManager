package net.plotshop.manager.mixin;

import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.plotshop.manager.RegisterBarrelScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the registration GUI has just produced sign text, fills it into the four
 * rows of the next sign edit screen that opens, so the player only has to press
 * "Done" instead of trying to paste text (Minecraft sign editors reject
 * clipboard paste).
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin implements AbstractSignEditScreenAccessor {

    @Inject(method = "init", at = @At("TAIL"))
    private void plotshop$fillPendingSignText(CallbackInfo ci) {
        String[] lines = RegisterBarrelScreen.consumePendingSignLines();
        if (lines == null) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            setCurrentRow(i);
            invokeSetCurrentRowMessage(i < lines.length && lines[i] != null ? lines[i] : "");
        }
    }
}
