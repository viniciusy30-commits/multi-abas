package com.vinicius.meular

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/** cat: 0 Sala, 1 Quarto, 2 Cozinha, 3 Decoração */
enum class Type(val nome: String, val w: Int, val h: Int, val preco: Int, val alt: Float, val cat: Int) {
    CHAIR("Cadeira", 1, 1, 20, 1.0f, 2),
    TABLE("Mesa de jantar", 2, 1, 60, 1.25f, 2),
    BED("Cama", 1, 2, 120, 1.1f, 1),
    SOFA("Sofá", 2, 1, 150, 1.05f, 0),
    SHELF("Estante", 2, 1, 100, 1.9f, 0),
    PLANT("Planta", 1, 1, 30, 1.5f, 3),
    RUG("Tapete", 2, 2, 40, 0.07f, 3),
    TV("TV com rack", 2, 1, 250, 1.4f, 0),
    FRIDGE("Geladeira", 1, 1, 300, 1.9f, 2),
    STOVE("Fogão", 1, 1, 220, 1.2f, 2),
    LAMP("Abajur de chão", 1, 1, 35, 1.6f, 3),
    NIGHT("Criado-mudo", 1, 1, 45, 0.9f, 1),
    COFFEE("Mesa de centro", 2, 1, 70, 0.55f, 0),
    WARDROBE("Guarda-roupa", 2, 1, 180, 2.1f, 1)
}

class Item(val type: Type, var x: Int, var y: Int, var rot: Int = 0) {
    var born = 0L
    val w: Int get() = if (rot % 2 == 1) type.h else type.w
    val h: Int get() = if (rot % 2 == 1) type.w else type.h
}

class GameState(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("meular", Context.MODE_PRIVATE)
    var money = 50
    var gw = 6
    var gh = 5
    var floorIdx = 0
    var wallIdx = 0
    var ownFloors = 1
    var ownWalls = 1
    var sens = 1
    var avatar = Avatar()
    var avatarSet = false
    val items = CopyOnWriteArrayList<Item>()

    init {
        floorIdx = prefs.getInt("floor", 0).coerceIn(0, Finish.floors.size - 1)
        wallIdx = prefs.getInt("wall", 0).coerceIn(0, Finish.walls.size - 1)
        ownFloors = prefs.getInt("ownF", 1) or 1
        ownWalls = prefs.getInt("ownW", 1) or 1
        sens = prefs.getInt("sens", 1).coerceIn(0, 2)
        Avatar.decode(prefs.getString("avatar", null))?.let { avatar = it; avatarSet = true }
        CuteColor.setColors(avatar.cc)
        CuteChar.setWear(avatar.wearKeys())
        val raw = prefs.getString("state", null)
        var ok = false
        if (raw != null) {
            try {
                val parts = raw.split("|")
                money = parts[0].toInt(); gw = parts[1].toInt(); gh = parts[2].toInt()
                if (parts.size > 3 && parts[3].isNotEmpty()) {
                    for (s in parts[3].split(";")) {
                        val f = s.split(",")
                        items.add(Item(Type.valueOf(f[0]), f[1].toInt(), f[2].toInt(), f[3].toInt() % 4))
                    }
                }
                ok = true
            } catch (e: Exception) {
                items.clear(); money = 50; gw = 6; gh = 5
            }
        }
        if (!ok) {
            items.add(Item(Type.BED, 0, 0))
            items.add(Item(Type.TABLE, 3, 2))
            items.add(Item(Type.CHAIR, 2, 2))
            save()
        }
    }

    fun nivelCasa(): Int = gw - 5

    fun precoAmpliar(): Int = 200 * nivelCasa()

    fun podeAmpliar(): Boolean = gw < 10

    fun canPlace(item: Item, nx: Int, ny: Int, rot: Int): Boolean {
        val w = if (rot % 2 == 1) item.type.h else item.type.w
        val h = if (rot % 2 == 1) item.type.w else item.type.h
        if (nx < 0 || ny < 0 || nx + w > gw || ny + h > gh) return false
        if (item.type == Type.RUG) return true
        return items.none { o ->
            o !== item && o.type != Type.RUG &&
                nx < o.x + o.w && nx + w > o.x && ny < o.y + o.h && ny + h > o.y
        }
    }

    fun rotate(item: Item): Boolean {
        val nr = (item.rot + 1) % 4
        val w = if (nr % 2 == 1) item.type.h else item.type.w
        val h = if (nr % 2 == 1) item.type.w else item.type.h
        val nx = item.x.coerceIn(0, maxOf(0, gw - w))
        val ny = item.y.coerceIn(0, maxOf(0, gh - h))
        if (!canPlace(item, nx, ny, nr)) return false
        item.x = nx; item.y = ny; item.rot = nr
        return true
    }

    fun findSpot(t: Type): Pair<Int, Int>? = findSpotNear(t, 0f, 0f)

    /** Primeiro lugar livre mais próximo do ponto (fx, fz) em coordenadas de mundo. */
    fun findSpotNear(t: Type, fx: Float, fz: Float): Pair<Int, Int>? {
        val probe = Item(t, 0, 0)
        var best: Pair<Int, Int>? = null
        var bd = Float.MAX_VALUE
        for (y in 0 until gh) for (x in 0 until gw) {
            if (!canPlace(probe, x, y, 0)) continue
            val dx = x + t.w / 2f - fx
            val dz = y + t.h / 2f - fz
            val d = dx * dx + dz * dz
            if (d < bd) { bd = d; best = Pair(x, y) }
        }
        return best
    }

    fun save() {
        val s = items.joinToString(";") { "${it.type.name},${it.x},${it.y},${it.rot}" }
        prefs.edit()
            .putString("state", "$money|$gw|$gh|$s")
            .putInt("floor", floorIdx).putInt("wall", wallIdx)
            .putInt("ownF", ownFloors).putInt("ownW", ownWalls)
            .putInt("sens", sens)
            .putString("avatar", avatar.encode())
            .apply()
    }
}
