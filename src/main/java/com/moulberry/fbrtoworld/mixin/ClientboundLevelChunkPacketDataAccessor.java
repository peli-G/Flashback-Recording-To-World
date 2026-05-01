package com.moulberry.fbrtoworld.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ClientboundLevelChunkPacketData.class)
public interface ClientboundLevelChunkPacketDataAccessor {

    @Accessor("buffer")
    byte[] fbr$getBuffer();

    // BlockEntityInfo is private in 1.21.1+; use List<?> and access fields via reflection
    @Accessor("blockEntitiesData")
    List<?> fbr$getBlockEntitiesData();
}
