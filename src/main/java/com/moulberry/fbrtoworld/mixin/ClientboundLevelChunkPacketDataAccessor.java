package com.moulberry.fbrtoworld.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundLevelChunkPacketData.class)
public interface ClientboundLevelChunkPacketDataAccessor {

    @Accessor("buffer")
    byte[] fbr$getBuffer();
}
