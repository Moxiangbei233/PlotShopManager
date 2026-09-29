package net.plotshop.manager.mixin;

import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private sign-editor state so the registration GUI's generated
 * sign text can be filled into the four rows. Minecraft 1.20.4's sign editor
 * does not use {@code TextFieldWidget}s, so this is the only way to set the
 * text programmatically.
 */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
    @Accessor("currentRow")
    void setCurrentRow(int row);

    @Invoker("setCurrentRowMessage")
    void invokeSetCurrentRowMessage(String message);
}
