package com.jirakj.pixelagents.services

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirakj.pixelagents.Constants
import com.jirakj.pixelagents.toolWindow.WebviewBridge
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

@Service(Service.Level.PROJECT)
class AssetLoaderService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(AssetLoaderService::class.java)
        private val gson = Gson()
        private const val CHAR_COUNT = 6
        private const val CHAR_FRAME_W = 16
        private const val CHAR_FRAME_H = 32
        private const val CHAR_FRAMES_PER_ROW = 7
        private const val CHAR_DIRECTION_ROWS = 3
        private const val FLOOR_TILE_SIZE = 16
        private const val WALL_PIECE_W = 16
        private const val WALL_PIECE_H = 32
        private const val WALL_GRID_COLS = 4
        private const val WALL_GRID_ROWS = 4

        fun getInstance(project: Project): AssetLoaderService =
            project.getService(AssetLoaderService::class.java)
    }

    private var bridge: WebviewBridge? = null

    fun setBridge(bridge: WebviewBridge) {
        this.bridge = bridge
    }

    /**
     * Load all assets and send to webview in correct order:
     * characterSpritesLoaded → floorTilesLoaded → wallTilesLoaded → furnitureAssetsLoaded → layoutLoaded
     */
    fun loadAndSendAllAssets() {
        val br = bridge ?: return

        // Character sprites
        val characters = loadCharacterSprites()
        if (characters != null) {
            br.postMessage("characterSpritesLoaded", mapOf("characters" to characters))
            LOG.info("Sent ${characters.size} character sprites to webview")
        }

        // Floor tiles
        val floorSprites = loadFloorTiles()
        if (floorSprites != null) {
            br.postMessage("floorTilesLoaded", mapOf("sprites" to floorSprites))
            LOG.info("Sent ${floorSprites.size} floor tile patterns to webview")
        }

        // Wall tiles
        val wallSets = loadWallTiles()
        if (wallSets != null) {
            br.postMessage("wallTilesLoaded", mapOf("sets" to wallSets))
            LOG.info("Sent ${wallSets.size} wall tile set(s) to webview")
        }

        // Furniture assets
        val furniture = loadFurnitureAssets()
        if (furniture != null) {
            br.postMessage("furnitureAssetsLoaded", mapOf(
                "catalog" to furniture.first,
                "sprites" to furniture.second
            ))
            LOG.info("Sent ${furniture.first.size} furniture assets to webview")
        }
    }

    // ── PNG → SpriteData conversion ──

    /**
     * Convert a BufferedImage region to SpriteData (2D array of hex color strings).
     * Pixels with alpha < PNG_ALPHA_THRESHOLD are represented as empty strings.
     */
    private fun imageToSpriteData(
        img: BufferedImage,
        x: Int, y: Int,
        width: Int, height: Int
    ): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (row in 0 until height) {
            val cols = mutableListOf<String>()
            for (col in 0 until width) {
                val px = x + col
                val py = y + row
                if (px >= img.width || py >= img.height) {
                    cols.add("")
                    continue
                }
                val argb = img.getRGB(px, py)
                val a = (argb shr 24) and 0xFF
                if (a < Constants.PNG_ALPHA_THRESHOLD) {
                    cols.add("")
                } else {
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    if (a == 255) {
                        cols.add("#%02x%02x%02x".format(r, g, b))
                    } else {
                        cols.add("#%02x%02x%02x%02x".format(r, g, b, a))
                    }
                }
            }
            rows.add(cols)
        }
        return rows
    }

    private fun imageToSpriteData(img: BufferedImage): List<List<String>> {
        return imageToSpriteData(img, 0, 0, img.width, img.height)
    }

    // ── Character sprites ──

    private fun loadCharacterSprites(): List<Map<String, List<List<List<String>>>>>? {
        val characters = mutableListOf<Map<String, List<List<List<String>>>>>()

        for (ci in 0 until CHAR_COUNT) {
            val stream = getAssetStream("characters/char_$ci.png") ?: run {
                LOG.warn("Character sprite char_$ci.png not found")
                return null
            }
            val img = ImageIO.read(stream) ?: run {
                LOG.warn("Failed to decode char_$ci.png")
                return null
            }

            val directions = mutableMapOf<String, List<List<List<String>>>>()
            val dirNames = listOf("down", "up", "right")

            for ((dirIdx, dirName) in dirNames.withIndex()) {
                val frames = mutableListOf<List<List<String>>>()
                for (frame in 0 until CHAR_FRAMES_PER_ROW) {
                    val x = frame * CHAR_FRAME_W
                    val y = dirIdx * CHAR_FRAME_H
                    frames.add(imageToSpriteData(img, x, y, CHAR_FRAME_W, CHAR_FRAME_H))
                }
                directions[dirName] = frames
            }

            characters.add(directions)
        }

        return characters
    }

    // ── Floor tiles ──

    private fun loadFloorTiles(): List<List<List<String>>>? {
        val sprites = mutableListOf<List<List<String>>>()
        var index = 0
        while (true) {
            val stream = getAssetStream("floors/floor_$index.png") ?: break
            val img = ImageIO.read(stream) ?: break
            sprites.add(imageToSpriteData(img, 0, 0, FLOOR_TILE_SIZE, FLOOR_TILE_SIZE))
            index++
        }
        return if (sprites.isEmpty()) null else sprites
    }

    // ── Wall tiles ──

    private fun loadWallTiles(): List<List<List<List<String>>>>? {
        val sets = mutableListOf<List<List<List<String>>>>()
        var index = 0
        while (true) {
            val stream = getAssetStream("walls/wall_$index.png") ?: break
            val img = ImageIO.read(stream) ?: break

            // Parse 4×4 grid of 16×32 pieces (16 bitmask sprites)
            val pieces = mutableListOf<List<List<String>>>()
            for (row in 0 until WALL_GRID_ROWS) {
                for (col in 0 until WALL_GRID_COLS) {
                    val x = col * WALL_PIECE_W
                    val y = row * WALL_PIECE_H
                    pieces.add(imageToSpriteData(img, x, y, WALL_PIECE_W, WALL_PIECE_H))
                }
            }
            sets.add(pieces)
            index++
        }
        return if (sets.isEmpty()) null else sets
    }

    // ── Furniture assets ──

    private fun loadFurnitureAssets(): Pair<List<Map<String, Any?>>, Map<String, List<List<String>>>>? {
        val catalog = mutableListOf<Map<String, Any?>>()
        val sprites = mutableMapOf<String, List<List<String>>>()

        val furnitureDir = getAssetDir("furniture") ?: return null
        val dirs = furnitureDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?: return null

        for (dir in dirs) {
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) continue

            try {
                val manifest = gson.fromJson(manifestFile.readText(), JsonObject::class.java)
                val assets = flattenManifest(manifest, dir)

                for (asset in assets) {
                    val assetId = asset["id"] as? String ?: continue
                    val fileName = asset["file"] as? String ?: continue
                    val width = (asset["width"] as? Number)?.toInt() ?: continue
                    val height = (asset["height"] as? Number)?.toInt() ?: continue

                    val pngFile = File(dir, fileName)
                    if (!pngFile.exists()) continue

                    try {
                        val img = ImageIO.read(pngFile) ?: continue
                        sprites[assetId] = imageToSpriteData(img, 0, 0, width, height)
                        catalog.add(asset)
                    } catch (e: Exception) {
                        LOG.warn("Error loading asset $assetId: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Error processing ${dir.name}: ${e.message}")
            }
        }

        return if (catalog.isEmpty()) null else Pair(catalog, sprites)
    }

    /**
     * Flatten a furniture manifest into a list of asset descriptors.
     * Handles both single-asset and group manifests.
     */
    private fun flattenManifest(manifest: JsonObject, dir: File): List<Map<String, Any?>> {
        val assets = mutableListOf<Map<String, Any?>>()
        val id = manifest.get("id")?.asString ?: return assets
        val name = manifest.get("name")?.asString ?: id
        val category = manifest.get("category")?.asString ?: "misc"
        val canPlaceOnWalls = manifest.get("canPlaceOnWalls")?.asBoolean
        val canPlaceOnSurfaces = manifest.get("canPlaceOnSurfaces")?.asBoolean
        val backgroundTiles = manifest.get("backgroundTiles")?.asInt

        val type = manifest.get("type")?.asString ?: "group"

        if (type == "asset") {
            val file = manifest.get("file")?.asString ?: "$id.png"
            assets.add(mapOf(
                "id" to id,
                "name" to name,
                "label" to name,
                "category" to category,
                "file" to file,
                "width" to manifest.get("width")?.asInt,
                "height" to manifest.get("height")?.asInt,
                "footprintW" to manifest.get("footprintW")?.asInt,
                "footprintH" to manifest.get("footprintH")?.asInt,
                "isDesk" to (category == "desks"),
                "canPlaceOnWalls" to canPlaceOnWalls,
                "canPlaceOnSurfaces" to canPlaceOnSurfaces,
                "backgroundTiles" to backgroundTiles,
                "groupId" to id
            ))
        } else {
            // Group manifest — flatten members recursively
            val members = manifest.getAsJsonArray("members")
            if (members != null) {
                flattenMembers(members, id, name, category, canPlaceOnWalls, canPlaceOnSurfaces, backgroundTiles, assets)
            }
        }

        return assets
    }

    private fun flattenMembers(
        members: JsonArray,
        groupId: String,
        groupName: String,
        category: String,
        canPlaceOnWalls: Boolean?,
        canPlaceOnSurfaces: Boolean?,
        backgroundTiles: Int?,
        assets: MutableList<Map<String, Any?>>
    ) {
        for (member in members) {
            if (!member.isJsonObject) continue
            val m = member.asJsonObject
            val memberType = m.get("type")?.asString ?: continue

            if (memberType == "asset") {
                val mId = m.get("id")?.asString ?: continue
                val mName = m.get("name")?.asString ?: mId
                val file = m.get("file")?.asString ?: "$mId.png"
                val orientation = m.get("orientation")?.asString
                val state = m.get("state")?.asString

                assets.add(mapOf(
                    "id" to mId,
                    "name" to mName,
                    "label" to "$groupName${if (orientation != null) " ($orientation)" else ""}",
                    "category" to category,
                    "file" to file,
                    "width" to m.get("width")?.asInt,
                    "height" to m.get("height")?.asInt,
                    "footprintW" to m.get("footprintW")?.asInt,
                    "footprintH" to m.get("footprintH")?.asInt,
                    "isDesk" to (category == "desks"),
                    "canPlaceOnWalls" to (m.get("canPlaceOnWalls")?.asBoolean ?: canPlaceOnWalls),
                    "canPlaceOnSurfaces" to (m.get("canPlaceOnSurfaces")?.asBoolean ?: canPlaceOnSurfaces),
                    "backgroundTiles" to (m.get("backgroundTiles")?.asInt ?: backgroundTiles),
                    "groupId" to groupId,
                    "orientation" to orientation,
                    "state" to state
                ))
            } else if (memberType == "group") {
                val subMembers = m.getAsJsonArray("members")
                if (subMembers != null) {
                    flattenMembers(subMembers, groupId, groupName, category, canPlaceOnWalls, canPlaceOnSurfaces, backgroundTiles, assets)
                }
            }
        }
    }

    // ── Default layout ──

    fun loadDefaultLayout(): String? {
        // Try versioned layouts first
        var bestRevision = 0
        var bestStream: InputStream? = null

        for (rev in 1..100) {
            val stream = getAssetStream("default-layout-$rev.json")
            if (stream != null) {
                bestRevision = rev
                bestStream?.close()
                bestStream = stream
            } else {
                break
            }
        }

        if (bestStream == null) {
            bestStream = getAssetStream("default-layout.json")
        }

        return bestStream?.use { it.bufferedReader().readText() }
    }

    // ── Asset resource helpers ──

    private fun getAssetStream(relativePath: String): InputStream? {
        // Try plugin resources first
        val resourceStream = javaClass.classLoader.getResourceAsStream("assets/$relativePath")
        if (resourceStream != null) return resourceStream

        // Fallback: check src/main/resources/assets (dev mode)
        val devFile = File("src/main/resources/assets/$relativePath")
        if (devFile.exists()) return devFile.inputStream()

        return null
    }

    private fun getAssetDir(relativePath: String): File? {
        // Try src/main/resources/assets first (works in dev)
        val devDir = File("src/main/resources/assets/$relativePath")
        if (devDir.exists() && devDir.isDirectory) return devDir

        // In production, resources are in JAR — extract or use URL
        val resourceUrl = javaClass.classLoader.getResource("assets/$relativePath")
        if (resourceUrl != null && resourceUrl.protocol == "file") {
            val file = File(resourceUrl.toURI())
            if (file.isDirectory) return file
        }

        return null
    }

    override fun dispose() {}
}
