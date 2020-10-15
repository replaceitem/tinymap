package link.infra.tinymap;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MaterialColor;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;

public class TileGenerator {
	private final MinecraftServer server;

	public TileGenerator(MinecraftServer server) {
		this.server = server;
	}

	private ServerWorld getWorldForName(String worldName) {
		for (ServerWorld world : server.getWorlds()) {
			if (world.getRegistryKey().getValue().toString().equals(worldName)) {
				return world;
			}
		}
		return null;
	}

	public static final int TILE_SIZE = 256;
	public static final int TILE_TO_BLOCK_SHIFT = -16;
	public static final int TILE_TO_CHUNK_SHIFT = -4;
	public static final int TILE_TO_REGION_SHIFT = TILE_TO_CHUNK_SHIFT + 5;

	public static int rightShiftButReversible(int num, int shift) {
		if (shift < 0) {
			return num << (-shift);
		} else {
			return num >> shift;
		}
	}

	private static final WeakHashMap<ServerWorld, BlockDigger> diggers = new WeakHashMap<>();

	public ByteBuffer getTile(String worldName, int x, int z, int zoom) throws IOException {
		ServerWorld world = getWorldForName(worldName);
		if (world == null) {
			return null;
		}

		if (zoom > 0) {
			return null;
		}
		int zoomShift = -zoom;

		BlockDigger.Session digger = diggers.computeIfAbsent(world, _world -> new BlockDigger(server, _world)).new Session();

		if (digger.testTileExists(x, z, zoomShift)) {
			int[] colors = getColorsFromWorld(world, x, z, zoomShift, digger);
			DataBufferInt buf = new DataBufferInt(colors, colors.length);
			// ABGR -> RGBA
			int[] masks = new int[]{0xff, 0xff00, 0xff0000, 0xff000000};
			BufferedImage bufImg = new BufferedImage(new DirectColorModel(32, masks[0], masks[1], masks[2], masks[3]),
				Raster.createPackedRaster(buf, TILE_SIZE, TILE_SIZE, TILE_SIZE, masks, null), false, null);

			// TODO: write directly
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(bufImg, "png", baos);

//			ByteBuffer srcBuf = MemoryUtil.memAlloc(4 * 16 * 16);
//			srcBuf.asIntBuffer().put(colors);
//			srcBuf.flip();
//
//			CompletableFuture<ByteBuffer> pngDataFut = new CompletableFuture<>();
//
//			STBImageWrite.stbi_write_png_to_func((context, data, size) ->
//				pngDataFut.complete(STBIWriteCallback.getData(data, size)),
//				0, 16, 16, 4, srcBuf, 4 * 16);

			//return pngDataFut.join();
			return ByteBuffer.wrap(baos.toByteArray());
		} else {
			return null;
		}
	}

	// TODO: abstract the act of getting blocks
	private int[] getColorsFromWorld(ServerWorld world, int tileX, int tileZ, int zoomShift, BlockDigger.Session digger) {
		BlockSearcher searcher = new BlockSearcher(world);

		int chunkSize = TileGenerator.rightShiftButReversible(1, TileGenerator.TILE_TO_CHUNK_SHIFT - zoomShift);
		int chunkOriginX = TileGenerator.rightShiftButReversible(tileX, TileGenerator.TILE_TO_CHUNK_SHIFT - zoomShift);
		int chunkOriginZ = TileGenerator.rightShiftButReversible(tileZ, TileGenerator.TILE_TO_CHUNK_SHIFT - zoomShift);
		int[] colors = new int[TILE_SIZE * TILE_SIZE];
		boolean hasCeiling = world.getDimension().hasCeiling();

		for (int chunkOffX = 0; chunkOffX < chunkSize; chunkOffX++) {
			Chunk chunkBefore = digger.getChunkView(chunkOriginX + chunkOffX, chunkOriginZ + chunkSize - 1);
			ChunkPos chunkPosBefore = new ChunkPos(chunkOriginX + chunkOffX, chunkOriginZ + chunkSize - 1);
			Heightmap chunkBeforeHeightmap = null;
			if (chunkBefore != null) {
				chunkBeforeHeightmap = chunkBefore.getHeightmap(Heightmap.Type.WORLD_SURFACE);
			}
			// TODO: fix shadE!!!!!!
			int[] lastHeights = new int[16];

			for (int chunkOffZ = 0; chunkOffZ < chunkSize; chunkOffZ++) {
				Chunk chunk = digger.getChunkView(chunkOriginX + chunkOffX, chunkOriginZ + chunkOffZ);
				ChunkPos chunkPos = new ChunkPos(chunkOriginX + chunkOffX, chunkOriginZ + chunkOffZ);
				if (chunk == null) {
					continue;
				}
				Heightmap chunkHeightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);

				int lastHeight = 0;
				for (int xOff = 0; xOff < 16; xOff++) {
					// TODO: check isEmpty???
					if (chunkBefore != null) {
						// Get first line, to calculate proper shade
						if (hasCeiling) {
							searcher.searchForBlockCeil(chunkBefore, xOff, 15, chunkPosBefore.getStartX(), chunkPosBefore.getStartZ());
						} else {
							searcher.searchForBlock(chunkBefore, chunkBeforeHeightmap, xOff, 15, chunkPosBefore.getStartX(), chunkPosBefore.getStartZ());
						}
						lastHeight = searcher.height;
					}

					for (int zOff = 0; zOff < 16; zOff++) {
						if (hasCeiling) {
							searcher.searchForBlockCeil(chunk, xOff, zOff, chunkPos.getStartX(), chunkPos.getStartZ());
						} else {
							searcher.searchForBlock(chunk, chunkHeightmap, xOff, zOff, chunkPos.getStartX(), chunkPos.getStartZ());
						}

						if (searcher.height > 0 && !searcher.blockState.getFluidState().isEmpty()) {
							searcher.calcWaterDepth(chunk);
						}

						MaterialColor matColor = searcher.blockState.getTopMaterialColor(world, searcher.pos);
						int shade;

						if (matColor == MaterialColor.WATER) {
							double shadeTest = (double) searcher.waterDepth * 0.1D + (double) (xOff + zOff & 1) * 0.2D;
							shade = 1;
							if (shadeTest < 0.5D) {
								shade = 2;
							}

							if (shadeTest > 0.9D) {
								shade = 0;
							}
						} else {
							double shadeTest = (searcher.height - lastHeight) * 4.0D / 5.0D + ((double) (xOff + zOff & 1) - 0.5D) * 0.4D;
							shade = 1;
							if (shadeTest > 0.6D) {
								shade = 2;
							}
							if (shadeTest < -0.6D) {
								shade = 0;
							}
						}

						lastHeight = searcher.height;
						colors[(zOff + (chunkOffZ * 16)) * TILE_SIZE + (xOff + (chunkOffX * 16))] = getRenderColor(matColor, shade);
					}
				}
			}
		}

		return colors;
	}

//	private int[] getColorsFromWorld(ServerWorld world, ChunkPos chunkPos) {
//		// TODO: fail if chunk is empty?
//		WorldChunk worldChunk = world.getChunk(chunkPos.x, chunkPos.z);
//		Heightmap worldChunkHeightmap = worldChunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
//		ChunkPos chunkPosBefore = new ChunkPos(chunkPos.x, chunkPos.z - 1);
//		WorldChunk worldChunkBefore = worldChunk.getWorld().getChunk(chunkPosBefore.x, chunkPosBefore.z);
//		Heightmap worldChunkBeforeHeightmap = null;
//		if (worldChunkBefore != null) {
//			worldChunkBeforeHeightmap = worldChunkBefore.getHeightmap(Heightmap.Type.WORLD_SURFACE);
//		}
//
//		int[] colors = new int[16 * 16];
//		BlockSearcher searcher = new BlockSearcher(world);
//
//		boolean hasCeiling = world.getDimension().hasCeiling();
//
//		int lastHeight = 0;
//		for (int xOff = 0; xOff < 16; xOff++) {
//			if (worldChunkBefore != null && !worldChunkBefore.isEmpty()) {
//				// Get first line, to calculate proper shade
//				if (hasCeiling) {
//					searcher.searchForBlockCeil(worldChunkBefore, xOff, 15, chunkPosBefore.getStartX(), chunkPosBefore.getStartZ());
//				} else {
//					searcher.searchForBlock(worldChunkBefore, worldChunkBeforeHeightmap, xOff, 15, chunkPosBefore.getStartX(), chunkPosBefore.getStartZ());
//				}
//				lastHeight = searcher.height;
//			}
//
//			for (int zOff = 0; zOff < 16; zOff++) {
//				if (hasCeiling) {
//					searcher.searchForBlockCeil(worldChunk, xOff, zOff, chunkPos.getStartX(), chunkPos.getStartZ());
//				} else {
//					searcher.searchForBlock(worldChunk, worldChunkHeightmap, xOff, zOff, chunkPos.getStartX(), chunkPos.getStartZ());
//				}
//
//				if (searcher.height > 0 && !searcher.blockState.getFluidState().isEmpty()) {
//					searcher.calcWaterDepth(worldChunk);
//				}
//
//				MaterialColor matColor = searcher.blockState.getTopMaterialColor(world, searcher.pos);
//				int shade;
//
//				if (matColor == MaterialColor.WATER) {
//					double shadeTest = (double) searcher.waterDepth * 0.1D + (double) (xOff + zOff & 1) * 0.2D;
//					shade = 1;
//					if (shadeTest < 0.5D) {
//						shade = 2;
//					}
//
//					if (shadeTest > 0.9D) {
//						shade = 0;
//					}
//				} else {
//					double shadeTest = (searcher.height - lastHeight) * 4.0D / 5.0D + ((double) (xOff + zOff & 1) - 0.5D) * 0.4D;
//					shade = 1;
//					if (shadeTest > 0.6D) {
//						shade = 2;
//					}
//					if (shadeTest < -0.6D) {
//						shade = 0;
//					}
//				}
//
//				lastHeight = searcher.height;
//				colors[zOff * 16 + xOff] = getRenderColor(matColor, shade);
//			}
//		}
//
//		return colors;
//	}

	private static final class BlockSearcher {
		public final BlockPos.Mutable pos = new BlockPos.Mutable();
		private final BlockPos.Mutable depthTestPos = new BlockPos.Mutable();
		public BlockState blockState;
		public int height;
		public int waterDepth;
		private final ServerWorld world;

		public BlockSearcher(ServerWorld world) {
			this.world = world;
		}

		void searchForBlock(BlockView chunk, Heightmap surfaceHeightmap, int x, int z, int chunkStartX, int chunkStartZ) {
			height = surfaceHeightmap.get(x & 15, z & 15);
			pos.set(chunkStartX + x, height, chunkStartZ + z);
			if (height <= 1) {
				blockState = Blocks.BEDROCK.getDefaultState();
			} else {
				do {
					pos.setY(--height);
					blockState = chunk.getBlockState(pos);
				} while (blockState.getTopMaterialColor(world, pos) == MaterialColor.CLEAR && height > 0);
			}
		}

		void calcWaterDepth(BlockView chunk) {
			int heightTemp = height - 1;
			waterDepth = 0;
			depthTestPos.set(pos);

			BlockState depthTestBlock;
			do {
				depthTestPos.setY(heightTemp--);
				depthTestBlock = chunk.getBlockState(depthTestPos);
				++waterDepth;
			} while (heightTemp > 0 && !depthTestBlock.getFluidState().isEmpty());

			blockState = TileGenerator.getFluidStateIfVisible(world, blockState, depthTestPos);
		}

		void searchForBlockCeil(BlockView chunk, int x, int z, int chunkStartX, int chunkStartZ) {
			height = 85;
			boolean brokeThroughCeil = false;
			pos.set(chunkStartX + x, height, chunkStartZ + z);
			BlockState firstBlockState = chunk.getBlockState(pos);
			blockState = firstBlockState;
			if (blockState.isAir()) {
				brokeThroughCeil = true;
			}
			while ((!brokeThroughCeil || blockState.getTopMaterialColor(world, pos) == MaterialColor.CLEAR) && height > 0) {
				pos.setY(--height);
				blockState = chunk.getBlockState(pos);
				if (blockState.isAir()) {
					brokeThroughCeil = true;
				}
			}
			if (!brokeThroughCeil) {
				blockState = firstBlockState;
				height = 85;
				pos.setY(height);
			}
		}
	}

	public static int getRenderColor(MaterialColor color, int shade) {
		int i = 220;
		if (shade == 3) {
			i = 135;
		}

		if (shade == 2) {
			i = 255;
		}

		if (shade == 1) {
			i = 220;
		}

		if (shade == 0) {
			i = 180;
		}

		int j = (color.color >> 16 & 255) * i / 255;
		int k = (color.color >> 8 & 255) * i / 255;
		int l = (color.color & 255) * i / 255;
		return -16777216 | l << 16 | k << 8 | j;
	}

	// TODO: is this needed?
	private static BlockState getFluidStateIfVisible(World world, BlockState state, BlockPos pos) {
		FluidState fluidState = state.getFluidState();
		return !fluidState.isEmpty() && !state.isSideSolidFullSquare(world, pos, Direction.UP) ? fluidState.getBlockState() : state;
	}

}
