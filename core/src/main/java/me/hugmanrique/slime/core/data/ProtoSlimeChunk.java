package me.hugmanrique.slime.core.data;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import me.hugmanrique.slime.core.SlimeInputStream;
import me.hugmanrique.slime.core.SlimeReaderUtil;
import net.minecraft.server.v1_8_R3.Chunk;
import net.minecraft.server.v1_8_R3.ChunkCoordIntPair;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityTypes;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NibbleArray;
import net.minecraft.server.v1_8_R3.TileEntity;
import net.minecraft.server.v1_8_R3.World;

/**
 * Represents a chunk that hasn't been loaded yet.
 */
public class ProtoSlimeChunk {

    private static final int HEIGHTMAP_ENTRIES = 256;
    private static final int BIOMES_LENGTH = 256;
    private static final int SECTIONS_PER_CHUNK = 16;
    private static final int BLOCKS_LENGTH = 4096;
    private static final int BLOCK_DATA_LENGTH = 2048;

    @VisibleForTesting
    public static boolean RECALC_BLOCK_COUNTS = true;

    public static ProtoSlimeChunk read(SlimeInputStream in, ChunkCoordIntPair coords) throws IOException {
        int[] heightMap = in.readIntArray(HEIGHTMAP_ENTRIES);
        byte[] biomes = in.readByteArray(BIOMES_LENGTH);

        // Read sections
        BitSet populatedSections = in.readBitSet(SECTIONS_PER_CHUNK / 8);
        ChunkSection[] sections = new ChunkSection[SECTIONS_PER_CHUNK];

        for (int y = 0; y < SECTIONS_PER_CHUNK; y++) {
            if (!populatedSections.get(y)) {
                // Non-populated, leave as null
                continue;
            }

            int yPos = y << 4;
            ChunkSection section = new ChunkSection(yPos, true); // skyLight

            in.readNibbleArray(section.getEmittedLightArray());

            byte[] blocks = in.readByteArray(BLOCKS_LENGTH);
            NibbleArray data = in.readNibbleArray();

            char[] blockIds = section.getIdArray();
            SlimeReaderUtil.readBlockIds(blockIds, blocks, data);

            in.readNibbleArray(section.getSkyLightArray());

            // Skip custom Hypixel data
            int hypixelBlocksLength = in.readShort();
            in.skipBytes(hypixelBlocksLength);

            if (RECALC_BLOCK_COUNTS) {
                section.recalcBlockCounts();
            }

            sections[y] = section;
        }

        return new ProtoSlimeChunk(coords, sections, biomes, heightMap);
    }

    private final ChunkCoordIntPair coords;
    private final ChunkSection[] sections;

    private final byte[] biomes;
    private final int[] heightMap;

    private List<NBTTagCompound> tileEntities;
    private List<NBTTagCompound> entities;

    private ProtoSlimeChunk(ChunkCoordIntPair coords, ChunkSection[] sections, byte[] biomes, int[] heightMap) {
        this.coords = coords;
        this.sections = sections;
        this.biomes = biomes;
        this.heightMap = heightMap;
    }

    public void addTileEntity(NBTTagCompound compound) {
        if (tileEntities == null) {
            tileEntities = new ArrayList<>();
        }

        tileEntities.add(compound);
    }

    public void addEntity(NBTTagCompound compound) {
        if (entities == null) {
            entities = new ArrayList<>();
        }

        entities.add(compound);
    }

    /**
     * Adds the entities of this proto chunk to the specified
     * Minecraft chunk.
     *
     * @param world the world the chunk is in
     * @param chunk the chunk to add the entities to
     */
    private void loadEntities(World world, Chunk chunk) {
        // Load entities
        world.timings.syncChunkLoadEntitiesTimer.startTiming();

        if (entities != null) {
            for (NBTTagCompound compound : entities) {
                Entity entity = EntityTypes.a(compound, world);

                chunk.g(true);

                if (entity == null) {
                    continue;
                }

                chunk.a(entity);

                // Add riding entities
                for (NBTTagCompound riding = compound; riding.hasKeyOfType("Riding", 10); riding = riding.getCompound("Riding")) {
                    Entity other = EntityTypes.a(riding.getCompound("Riding"), world);

                    if (other == null) {
                        break;
                    }

                    chunk.a(other);
                    entity.mount(other);

                    entity = other;
                }
            }
        }

        world.timings.syncChunkLoadEntitiesTimer.stopTiming();

        // Load tile entities
        world.timings.syncChunkLoadTileEntitiesTimer.startTiming();

        if (tileEntities != null) {
            for (NBTTagCompound compound : tileEntities) {
                TileEntity tileEntity = TileEntity.c(compound);

                if (tileEntity != null) {
                    chunk.a(tileEntity);
                }
            }
        }

        world.timings.syncChunkLoadTileEntitiesTimer.stopTiming();
    }

    /**
     * Converts this proto chunk into a Minecraft chunk.
     *
     * @param world the world the chunk is in
     * @return the loaded chunk
     */
    public Chunk load(World world) {
        Chunk chunk = new Chunk(world, coords.x, coords.z);

        chunk.a(heightMap);
        chunk.d(true); // TerrainPopulated
        chunk.e(true); // LightPopulated
        chunk.c(0); // InhabitedTime

        chunk.a(sections);
        chunk.a(biomes);

        loadEntities(world, chunk);

        return chunk;
    }

    public ChunkCoordIntPair getCoords() {
        return coords;
    }

    public ChunkSection[] getSections() {
        return sections;
    }

    public byte[] getBiomes() {
        return biomes;
    }

    public int[] getHeightMap() {
        return heightMap;
    }
}
