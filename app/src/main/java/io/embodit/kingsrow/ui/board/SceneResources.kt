package io.embodit.kingsrow.ui.board

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.kingsrow.domain.Layout
import io.embodit.kingsrow.ui.theme.KingsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The shared meshes / materials / shapes (design budget: <= 8 shared meshes, 5 lit materials + marks). Creating a
 * resource waits on the renderer (~1 s round trip on the emulator), so everything is created concurrently on
 * Dispatchers.IO (Xiangqi BoardScene.createResources) and kept global until [close].
 *
 * Geometry note (B12 / ui_gates "geometry"): the design package's asset list makes the board, the 28 discs and the
 * two trays procedural on purpose ("无 Tripo（没有具象子）": checkers men are plain turned discs with exact sizes);
 * the gate:allow lines say so per call.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var slab: MeshResource; private set
    lateinit var face: MeshResource; private set
    lateinit var disc: MeshResource; private set
    lateinit var cap: MeshResource; private set
    lateinit var unitBox: MeshResource; private set
    lateinit var ring: MeshResource; private set
    lateinit var dot: MeshResource; private set

    lateinit var oak: Material; private set
    lateinit var oakDark: Material; private set
    lateinit var faceMat: Material; private set
    lateinit var ebony: Material; private set
    lateinit var wine: Material; private set
    lateinit var capBlackMan: Material; private set
    lateinit var capBlackKing: Material; private set
    lateinit var capRedMan: Material; private set
    lateinit var capRedKing: Material; private set

    lateinit var selBlack: Material; private set
    lateinit var selWhite: Material; private set
    lateinit var selRed: Material; private set
    lateinit var blue: Material; private set
    lateinit var orange: Material; private set
    lateinit var red: Material; private set
    lateinit var green: Material; private set
    lateinit var white: Material; private set
    lateinit var amber: Material; private set
    lateinit var amberSoft: Material; private set
    lateinit var gold: Material; private set
    lateinit var coach: Material; private set

    lateinit var squareShape: ShapeResource; private set
    lateinit var manShape: ShapeResource; private set
    lateinit var kingShape: ShapeResource; private set
    lateinit var slabShape: ShapeResource; private set
    lateinit var trayShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        private fun pbr(color: Color, roughness: Float, metallic: Float = 0f): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                setRoughness(roughness)
                setMetallic(metallic)
            }

        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        /** Builds everything concurrently off the main thread. */
        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            val faceTex = async(io) { TextureResource.create(BoardTextures.boardFace(2048)).keep() }
            val capTex = listOf(
                KingsColors.Ebony to false, KingsColors.Ebony to true, KingsColors.Wine to false, KingsColors.Wine to true,
            ).map { (col, king) -> async(io) { TextureResource.create(BoardTextures.cap(col.toArgb(), king)).keep() } }
            listOf(
                // gate:allow geometry design assets: the oak board is procedural (slab + Canvas face), no Tripo
                async(io) { r.slab = MeshResource.createBox(Vector3(Layout.BOARD, Layout.THICK, Layout.BOARD), cornerRadius = 0.006f).keep() },
                async(io) { r.face = MeshResource.createPlane(Layout.BOARD - 0.004f, Layout.BOARD - 0.004f, 0f).keep() },
                // gate:allow geometry checkers men are turned discs Ø4.6 x 1.0 cm (design assets "基本几何 createCylinder")
                async(io) { r.disc = MeshResource.createCylinder(radius = Layout.PIECE_R, height = Layout.PIECE_H).keep() },
                async(io) { r.cap = MeshResource.createPlane(Layout.PIECE_R * 1.94f, Layout.PIECE_R * 1.94f, Layout.PIECE_R * 0.97f).keep() },
                // gate:allow geometry tray floor / rims and the thin frame bars of the board marks (design assets "托盘 程序化")
                async(io) { r.unitBox = MeshResource.createBox(Vector3(1f, 1f, 1f), cornerRadius = 0f).keep() },
                async(io) { r.ring = MeshResource.createTorus(outerRingRadius = 0.027f, innerRingRadius = 0.0235f).keep() },
                // gate:allow geometry blue target dot Ø1.6 cm, an abstract board mark (design theme.boardMarks)
                async(io) { r.dot = MeshResource.createCylinder(radius = 0.008f, height = 0.002f).keep() },
                async(io) { r.squareShape = ShapeResource.createBox(Vector3(0.055f, 0.003f, 0.055f)).keep() },
                async(io) { r.manShape = ShapeResource.createBox(Vector3(Layout.PIECE_R * 2, Layout.PIECE_H, Layout.PIECE_R * 2)).keep() },
                async(io) { r.kingShape = ShapeResource.createBox(Vector3(Layout.PIECE_R * 2, Layout.PIECE_H * 2, Layout.PIECE_R * 2)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                // the wood and the trays answer a pinch too (S10: pinch the board to reopen the panel)
                async(io) { r.slabShape = ShapeResource.createBox(Vector3(Layout.BOARD, Layout.THICK, Layout.BOARD)).keep() },
                async(io) { r.trayShape = ShapeResource.createBox(Vector3(Layout.TRAY_W, Layout.TRAY_RIM, Layout.TRAY_L)).keep() },
                async(io) { r.oak = pbr(KingsColors.Oak, roughness = 0.6f).keep() },
                async(io) { r.oakDark = pbr(KingsColors.OakEdge, roughness = 0.62f).keep() },
                async(io) { r.ebony = pbr(KingsColors.Ebony, roughness = 0.42f).keep() },
                async(io) { r.wine = pbr(KingsColors.Wine, roughness = 0.38f).keep() },
                async(io) { r.selBlack = unlit(KingsColors.SelectBlack).keep() },
                async(io) { r.selWhite = unlit(Color(0xFFFFFFFF)).keep() },
                async(io) { r.selRed = unlit(KingsColors.Primary).keep() },
                async(io) { r.blue = unlit(KingsColors.Target, 0.9f).keep() },
                async(io) { r.orange = unlit(KingsColors.CaptureLanding).keep() },
                async(io) { r.red = unlit(KingsColors.Error).keep() },
                async(io) { r.green = unlit(KingsColors.ChainGreen).keep() },
                async(io) { r.white = unlit(Color(0xFFFFFFFF), 0.85f).keep() },
                async(io) { r.amber = unlit(KingsColors.Tertiary).keep() },
                async(io) { r.amberSoft = unlit(KingsColors.Tertiary, 0.35f).keep() },
                async(io) { r.gold = unlit(KingsColors.Gold, 0.9f).keep() },
                async(io) { r.coach = unlit(Color(0xFFFFF4D6), 0.55f).keep() },
            ).awaitAll()
            r.faceMat = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(0.6f); setMetallic(0f); setBaseColorTexture(faceTex.await())
            }.keep()
            val caps = capTex.map { it.await() }
            fun capMat(i: Int, roughness: Float) = PhysicallyBasedMaterial.create(BlendingMode.MASKED).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f)); setRoughness(roughness); setMetallic(0f); setOpacityThreshold(0.5f); setBaseColorTexture(caps[i])
            }.keep()
            r.capBlackMan = capMat(0, 0.42f)
            r.capBlackKing = capMat(1, 0.35f)
            r.capRedMan = capMat(2, 0.38f)
            r.capRedKing = capMat(3, 0.32f)
            r
        }
    }
}
