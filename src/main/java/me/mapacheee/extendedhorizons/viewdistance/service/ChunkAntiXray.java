package me.mapacheee.extendedhorizons.viewdistance.service;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Random;

/**
 * Anti-Xray utility for obfuscating chunks
 * Replaces valuable ores with stone/deepslate and adds fake ores
 * 
 */
public class ChunkAntiXray {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    private static final Block[] VALUABLE_ORES = {
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.ANCIENT_DEBRIS,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE
    };

    private static final BlockState[] FAKE_ORES = {
            Blocks.DIAMOND_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(),
            Blocks.GOLD_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(),
            Blocks.IRON_ORE.defaultBlockState()
    };

    /**
     * Obfuscates a chunk by hiding real ores and adding fake ones
     */
    public static void obfuscateChunk(LevelChunk chunk, boolean hideOres, boolean addFakeOres, double fakeOreDensity) {
        Random random = new Random(chunk.getPos().toLong());
        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            int sectionY = chunk.getMinSectionY() + sectionIndex;
            int baseY = sectionY * 16;

            PalettedContainer<BlockState> blocks = section.getStates();

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = blocks.get(x, y, z);
                        if (state == null)
                            continue;

                        Block block = state.getBlock();
                        int worldY = baseY + y;

                        if (hideOres && isValuableOre(block)) {
                            BlockState replacement = worldY < 0 ? DEEPSLATE : STONE;
                            blocks.set(x, y, z, replacement);
                        } else if (addFakeOres && random.nextDouble() < fakeOreDensity) {
                            if (block == Blocks.STONE || block == Blocks.DEEPSLATE) {
                                BlockState fakeOre = getFakeOre(random, worldY);
                                blocks.set(x, y, z, fakeOre);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isValuableOre(Block block) {
        for (Block ore : VALUABLE_ORES) {
            if (block == ore) {
                return true;
            }
        }
        return false;
    }

    private static BlockState getFakeOre(Random random, int y) {
        if (y < 0) {
            return random.nextBoolean()
                    ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()
                    : Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
        } else {
            return FAKE_ORES[random.nextInt(FAKE_ORES.length)];
        }
    }
}
