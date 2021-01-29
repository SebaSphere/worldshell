package net.snakefangox.worldshell.mixin;

import java.util.Map;
import java.util.concurrent.Executor;

import com.google.common.collect.ImmutableList;
import net.snakefangox.worldshell.mixininterface.DynamicDimGen;
import net.snakefangox.worldshell.util.ServerWorldSupplier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.WorldGenerationProgressListenerFactory;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.util.snooper.SnooperListener;
import net.minecraft.util.thread.ReentrantThreadExecutor;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.border.WorldBorderListener;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.UnmodifiableLevelProperties;
import net.minecraft.world.level.storage.LevelStorage;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin extends ReentrantThreadExecutor<ServerTask> implements SnooperListener, CommandOutput, AutoCloseable, DynamicDimGen {

	@Final
	@Shadow
	protected SaveProperties saveProperties;
	@Final
	@Shadow
	protected DynamicRegistryManager.Impl registryManager;
	@Final
	@Shadow
	private Executor workerExecutor;
	@Final
	@Shadow
	private WorldGenerationProgressListenerFactory worldGenerationProgressListenerFactory;
	@Final
	@Shadow
	protected LevelStorage.Session session;
	@Final
	@Shadow
	private Map<RegistryKey<World>, ServerWorld> worlds;

	@Shadow public @Nullable abstract ServerWorld getWorld(RegistryKey<World> key);

	public MinecraftServerMixin(String string) {
		super(string);
	}

	public ServerWorld createDynamicDim(RegistryKey<World> worldRegistryKey, RegistryKey<DimensionType> dimensionTypeKey, ChunkGenerator chunkGenerator) {
		return createDynamicDim(worldRegistryKey, new DimensionOptions(() -> registryManager.get(Registry.DIMENSION_TYPE_KEY).get(dimensionTypeKey), chunkGenerator));
	}

	public ServerWorld createDynamicDim(RegistryKey<World> worldRegistryKey, DimensionOptions dimensionOptions) {
		return createDynamicDim(worldRegistryKey, dimensionOptions, ServerWorld::new);
	}

	@Override
	public ServerWorld createDynamicDim(RegistryKey<World> worldRegistryKey, DimensionOptions dimensionOptions, ServerWorldSupplier worldSupplier) {
		boolean isDebug = saveProperties.getGeneratorOptions().isDebugWorld();
		long seed = BiomeAccess.hashSeed(saveProperties.getGeneratorOptions().getSeed());
		ServerWorldProperties serverWorldProperties = saveProperties.getMainWorldProperties();
		DimensionType dimensionType = dimensionOptions.getDimensionType();
		ChunkGenerator chunkGenerator = dimensionOptions.getChunkGenerator();
		UnmodifiableLevelProperties unmodifiableLevelProperties = new UnmodifiableLevelProperties(saveProperties, serverWorldProperties);
		ServerWorld serverWorld = worldSupplier.create((MinecraftServer) (Object)this, workerExecutor, session, unmodifiableLevelProperties, worldRegistryKey, dimensionType, worldGenerationProgressListenerFactory.create(0), chunkGenerator, isDebug, seed, ImmutableList.of(), false);
		getWorld(World.OVERWORLD).getWorldBorder().addListener(new WorldBorderListener.WorldBorderSyncer(serverWorld.getWorldBorder()));
		worlds.put(worldRegistryKey, serverWorld);
		return serverWorld;
	}
}
