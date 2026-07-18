package com.chattriggers.ctjs.api.world

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus

object StructureFinder {
    private const val MAX_SCAN_RETRIES = 3
    private const val RETRY_DELAY_MS = 30L

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "V5-StructureFinder-Worker").apply { isDaemon = true }
    }

    private val stateLock = Any()
    private val pendingLock = Any()
    private val chunkResults = Long2ObjectOpenHashMap<List<FoundStructure>>(256)
    private val pendingScans = LongOpenHashSet(256)

    @Volatile
    private var cachedRenderArray = IntArray(0)

    @Volatile
    private var cachedLabelsArray = emptyArray<String>()

    @Volatile
    private var dirty = false

    @Volatile
    private var generation = 0

    @JvmStatic
    fun submitChunkScan(chunkX: Int, chunkZ: Int) {
        val key = ChunkPos.pack(chunkX, chunkZ)
        synchronized(pendingLock) {
            if (!pendingScans.add(key)) return
        }

        worker.execute { scanChunk(chunkX, chunkZ, key, generation, MAX_SCAN_RETRIES) }
    }

    @JvmStatic
    fun submitBlockUpdate(blockX: Int, blockY: Int, blockZ: Int) {
        submitChunkScan(blockX shr 4, blockZ shr 4)
    }

    @JvmStatic
    fun getRenderBlocksArray(): IntArray {
        if (!dirty) return cachedRenderArray
        synchronized(stateLock) {
            rebuildRenderCacheLocked()
            return cachedRenderArray
        }
    }

    @JvmStatic
    fun getRenderLabelsArray(): Array<String> = cachedLabelsArray

    @JvmStatic
    fun clear() {
        generation++
        synchronized(stateLock) {
            chunkResults.clear()
            cachedRenderArray = IntArray(0)
            cachedLabelsArray = emptyArray()
            dirty = false
        }
        synchronized(pendingLock) {
            pendingScans.clear()
        }
    }

    @JvmStatic
    fun shutdown() {
        worker.shutdownNow()
        clear()
    }

    private fun scanChunk(
        chunkX: Int,
        chunkZ: Int,
        key: Long,
        scanGeneration: Int,
        retriesLeft: Int,
    ) {
        var finished = true
        try {
            val world = Minecraft.getInstance().level ?: return
            val chunk = world.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
            if (chunk == null) {
                if (retriesLeft > 0) {
                    finished = false
                    worker.schedule(
                        { scanChunk(chunkX, chunkZ, key, scanGeneration, retriesLeft - 1) },
                        RETRY_DELAY_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
                return
            }

            val results = if (chunk.isEmpty) emptyList() else findStructures(chunk, chunkX, chunkZ)
            if (scanGeneration != generation) return

            synchronized(stateLock) {
                if (scanGeneration != generation) return
                if (results.isEmpty()) chunkResults.remove(key) else chunkResults.put(key, results)
                dirty = true
            }
        } finally {
            if (finished) {
                synchronized(pendingLock) {
                    pendingScans.remove(key)
                }
            }
        }
    }

    private fun findStructures(chunk: LevelChunk, chunkX: Int, chunkZ: Int): List<FoundStructure> {
        val world = Minecraft.getInstance().level ?: return emptyList()
        val structures = STRUCTURES.filter { it.region.intersects(chunkX, chunkZ) }
        if (structures.isEmpty()) return emptyList()

        val found = BooleanArray(structures.size)
        val results = ArrayList<FoundStructure>()
        var fairyX = 0
        var fairyY = 0
        var fairyZ = 0
        var fairyCount = 0
        val mutablePos = BlockPos.MutableBlockPos()
        val minY = world.minY
        val sections = chunk.sections

        for (sectionIndex in sections.indices) {
            val section = sections[sectionIndex]
            if (section.hasOnlyAir()) continue

            val baseY = minY + (sectionIndex shl 4)
            for (localY in 0..15) {
                val y = baseY + localY
                for (localZ in 0..15) {
                    val z = (chunkZ shl 4) + localZ
                    for (localX in 0..15) {
                        val x = (chunkX shl 4) + localX
                        val state = section.getBlockState(localX, localY, localZ)
                        if (state.isAir) continue

                        for (index in structures.indices) {
                            if (found[index]) continue
                            val structure = structures[index]
                            if (!structure.region.contains(x, y, z) || !structure.blocks[0]!!.matches(state)) continue
                            if (!matches(chunk, mutablePos, x, y, z, structure.blocks)) continue

                            if (structure.name == FAIRY_GROTTO) {
                                fairyX += x
                                fairyY += y
                                fairyZ += z
                                fairyCount++
                            } else {
                                results.add(
                                    FoundStructure(
                                        structure.name,
                                        x + structure.offsetX,
                                        y + structure.offsetY,
                                        z + structure.offsetZ
                                    )
                                )
                                found[index] = true
                            }
                        }
                    }
                }
            }
        }

        if (fairyCount > 0) results.add(FoundStructure(FAIRY_GROTTO, fairyX, fairyY, fairyZ, fairyCount))
        return results
    }

    private fun matches(
        chunk: LevelChunk,
        pos: BlockPos.MutableBlockPos,
        x: Int,
        y: Int,
        z: Int,
        blocks: List<BlockMatcher?>,
    ): Boolean {
        for (offset in 1 until blocks.size) {
            val matcher = blocks[offset] ?: continue
            if (!matcher.matches(chunk.getBlockState(pos.set(x, y + offset, z)))) return false
        }
        return true
    }

    private fun rebuildRenderCacheLocked() {
        if (!dirty) return
        if (chunkResults.isEmpty()) {
            cachedRenderArray = IntArray(0)
            cachedLabelsArray = emptyArray()
            dirty = false
            return
        }

        val out = ArrayList<FoundStructure>()
        var fairyX = 0L
        var fairyY = 0L
        var fairyZ = 0L
        var fairyCount = 0
        val iterator = chunkResults.values.iterator()
        while (iterator.hasNext()) {
            for (result in iterator.next()) {
                if (result.name == FAIRY_GROTTO) {
                    fairyX += result.x
                    fairyY += result.y
                    fairyZ += result.z
                    fairyCount += result.count
                } else {
                    out.add(result)
                }
            }
        }

        if (fairyCount > 0) {
            out.add(
                FoundStructure(
                    FAIRY_GROTTO,
                    (fairyX.toDouble() / fairyCount).roundToInt(),
                    (fairyY.toDouble() / fairyCount).roundToInt(),
                    (fairyZ.toDouble() / fairyCount).roundToInt(),
                ),
            )
        }
        cachedRenderArray = IntArray(out.size * 3)
        cachedLabelsArray = Array(out.size) { out[it].name }
        out.forEachIndexed { index, result ->
            cachedRenderArray[index * 3] = result.x
            cachedRenderArray[index * 3 + 1] = result.y
            cachedRenderArray[index * 3 + 2] = result.z
        }
        dirty = false
    }

    private enum class Region(
        private val xRange: IntRange? = null,
        private val zRange: IntRange? = null,
        private val maxY: Int? = null,
    ) {
        GOBLIN_HIDEOUT(202..512, 513..823),
        PRECURSOR_REMNANTS(513..823, 513..823),
        JUNGLE(202..512, 202..512),
        MINES_OF_DIVAN(513..823, 202..512),
        CRYSTAL_NUCLEUS(472..554, 472..554),
        MAGMA_FIELDS(202..823, 202..823, 79),
        ANY(202..823, 202..823);

        fun contains(x: Int, y: Int, z: Int): Boolean =
            (xRange == null || x in xRange) && (zRange == null || z in zRange) && (maxY == null || y <= maxY)

        fun intersects(chunkX: Int, chunkZ: Int): Boolean {
            val minX = chunkX shl 4
            val minZ = chunkZ shl 4
            return (xRange == null || minX <= xRange.last && minX + 15 >= xRange.first) &&
                    (zRange == null || minZ <= zRange.last && minZ + 15 >= zRange.first)
        }
    }

    private data class Structure(
        val name: String,
        val region: Region,
        val blocks: List<BlockMatcher?>,
        val offsetX: Int,
        val offsetY: Int,
        val offsetZ: Int,
    )

    private class BlockMatcher(token: String) {
        private val blockIds: List<String>
        private val slabType: SlabType?

        init {
            val parts = token.split(':', limit = 2)
            blockIds = parts[0].split('|')
            slabType = parts.getOrNull(1)?.let { SlabType.valueOf(it.uppercase()) }
        }

        fun matches(state: BlockState): Boolean {
            if (slabType != null && (state.block !is SlabBlock || state.getValue(SlabBlock.TYPE) != slabType)) return false

            val id = state.block.descriptionId.substringAfterLast('.')
            return blockIds.any {
                when (it) {
                    "#carpet" -> id.endsWith("_carpet")
                    "#leaves" -> id.endsWith("_leaves")
                    "#planks" -> id.endsWith("_planks")
                    "#skull" -> state.block is AbstractSkullBlock
                    "#stone_slab" -> id in STONE_SLABS
                    "#terracotta" -> id != "terracotta" && id.endsWith("_terracotta")
                    "#wall_sign" -> id.endsWith("_wall_sign")
                    "#wooden_slab" -> id.removeSuffix("_slab") in WOOD_TYPES
                    "#wool" -> id.endsWith("_wool")
                    else -> id == it
                }
            }
        }
    }

    private fun structure(
        name: String,
        region: Region,
        signature: String,
        offsetX: Int = 0,
        offsetY: Int = 0,
        offsetZ: Int = 0,
    ) = Structure(
        name,
        region,
        signature.split(',').map { if (it == "_") null else BlockMatcher(it) },
        offsetX,
        offsetY,
        offsetZ,
    )

    private val WOOD_TYPES = setOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")
    private val STONE_SLABS = setOf(
        "smooth_stone_slab",
        "sandstone_slab",
        "petrified_oak_slab",
        "cobblestone_slab",
        "brick_slab",
        "stone_brick_slab",
        "nether_brick_slab",
        "quartz_slab",
    )

    private const val FAIRY_GROTTO = "Fairy Grotto"

    // credits to https://github.com/RoseGoldIsntGay/GumTuneClient for these
    private val STRUCTURES = arrayOf(
        structure(
            "Goblin Hideout",
            Region.GOBLIN_HIDEOUT,
            "stone,acacia_log|dark_oak_log,acacia_log|dark_oak_log,acacia_log|dark_oak_log,acacia_log|dark_oak_log,cauldron",
            offsetY = 5
        ),
        structure(
            "Mines of Divan",
            Region.MINES_OF_DIVAN,
            "quartz_block,quartz_stairs,stone_brick_stairs,stone_bricks",
            offsetY = 5
        ),
        structure(
            "Precursor Remnants",
            Region.PRECURSOR_REMNANTS,
            "cobblestone,cobblestone,cobblestone,cobblestone,cobblestone_stairs,polished_andesite,polished_andesite,dark_oak_stairs",
            24,
            0,
            -17
        ),
        structure("Jungle Temple", Region.JUNGLE, "bedrock,clay,clay,#terracotta,#wool,#leaves,#leaves", -45, 47, -18),
        structure(
            "Goblin King",
            Region.GOBLIN_HIDEOUT,
            "#wool,dark_oak_stairs,dark_oak_stairs,dark_oak_stairs",
            1,
            -1,
            2
        ),
        structure(
            "Bal (Magma Fields)",
            Region.MAGMA_FIELDS,
            "lava,barrier,barrier,barrier,barrier,barrier,barrier,barrier,barrier,barrier,barrier",
            offsetY = 1
        ),
        structure(FAIRY_GROTTO, Region.ANY, "magenta_stained_glass"),
        structure(
            "Goblin Hall",
            Region.GOBLIN_HIDEOUT,
            "spruce_planks,_,spruce_stairs,spruce_stairs,_,_,spruce_stairs,spruce_stairs,_,_,spruce_stairs,spruce_stairs,_,spruce_planks",
            offsetY = 7
        ),
        structure(
            "Goblin Ring",
            Region.GOBLIN_HIDEOUT,
            "oak_fence,#skull,_,_,_,_,#wooden_slab:top,#wooden_slab:bottom,_,_,_,spruce_planks",
            offsetY = 11
        ),
        structure(
            "Grunt Bridge",
            Region.MINES_OF_DIVAN,
            "stone_brick_stairs,_,_,_,_,stone_bricks,stone_bricks,_,#stone_slab,stone_bricks,_,_,_,stone_bricks,#stone_slab",
            0,
            -1,
            -45
        ),
        structure(
            "Corleone Dock",
            Region.MINES_OF_DIVAN,
            "stone_bricks,stone_bricks,stone_bricks,stone_bricks,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,stone_bricks,stone_bricks,fire,stone_bricks",
            23,
            11,
            17
        ),
        structure(
            "Corleone Hole",
            Region.MINES_OF_DIVAN,
            "#stone_slab:bottom,_,_,_,_,_,_,_,_,_,_,_,_,_,#stone_slab:top,#stone_slab:double,_,#stone_slab:top,stone_bricks",
            0,
            -3,
            34
        ),
        structure("Grunt Rails", Region.MINES_OF_DIVAN, "spruce_planks,_,#wall_sign,_,_,_,_,spruce_planks,tnt"),
        structure(
            "Grunt Hero Statue",
            Region.MINES_OF_DIVAN,
            "#stone_slab:top,diorite,diorite,diorite,diorite,cobblestone,polished_andesite,cobblestone,cobblestone_stairs"
        ),
        structure(
            "Small Grunt Bridge",
            Region.MINES_OF_DIVAN,
            "spruce_stairs,spruce_stairs,_,spruce_stairs,oak_log,oak_fence,torch"
        ),
        structure(
            "Key Guardian Spiral",
            Region.JUNGLE,
            "jungle_stairs,#planks,glowstone,#carpet,_,#wooden_slab,_,jungle_stairs,stone,stone,stone"
        ),
        structure("Sludge Waterfalls", Region.JUNGLE, "stone,dirt,polished_granite,jungle_stairs,air,air,air,air,air"),
        structure(
            "Sludge Bridges",
            Region.JUNGLE,
            "jungle_planks,jungle_planks,jungle_planks,jungle_stairs,jungle_planks,jungle_planks,jungle_planks,jungle_stairs,jungle_planks,granite,granite"
        ),
        structure(
            "Yog Bridge",
            Region.MAGMA_FIELDS,
            "stone_bricks,stone_brick_stairs,stone_bricks,stone_brick_stairs,andesite,stone_brick_stairs,stone_brick_stairs,stone_brick_stairs,stone_bricks,stone_brick_stairs,andesite,stone_bricks,stone_bricks,stone_bricks,rail",
            offsetY = 15
        ),
        structure(
            "Odawa",
            Region.JUNGLE,
            "jungle_log,spruce_stairs,spruce_stairs,jungle_log,spruce_stairs,spruce_stairs,jungle_log,jungle_log,jungle_log,hay_block,yellow_terracotta"
        ),
        structure(
            "Mini Jungle Temple",
            Region.JUNGLE,
            "polished_andesite,andesite,stone_brick_stairs,andesite,andesite,stone,andesite,stone,andesite,andesite,stone_brick_stairs,andesite,stone,andesite"
        ),
        structure(
            "Precursor Tripwire Chamber",
            Region.PRECURSOR_REMNANTS,
            "diorite,diorite,#stone_slab:double,#stone_slab:double,#stone_slab:double,diorite,diorite,diorite,#stone_slab:double,#stone_slab:double,diorite,#stone_slab:double,#stone_slab:double,#stone_slab:double"
        ),
        structure(
            "Precursor Tall Pillars",
            Region.PRECURSOR_REMNANTS,
            "polished_diorite,polished_diorite,diorite,diorite,polished_diorite,polished_diorite,polished_andesite,diorite,diorite,diorite,diorite,polished_diorite,diorite,polished_andesite,polished_diorite,diorite,diorite,diorite,diorite,polished_diorite"
        ),
        structure(
            "Goblin Hole Camp",
            Region.GOBLIN_HIDEOUT,
            "netherrack,netherrack,oak_fence,oak_fence,oak_log,oak_log"
        ),
        structure(
            "Golden Dragon Nest",
            Region.ANY,
            "stone,red_terracotta,red_terracotta,red_terracotta,#skull,red_wool",
            0,
            -3,
            5
        ),
    )

    init {
        check(STRUCTURES.all { it.blocks.isNotEmpty() && it.blocks[0] != null })
    }

    private data class FoundStructure(val name: String, val x: Int, val y: Int, val z: Int, val count: Int = 1)
}
