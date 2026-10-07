package com.antifraud.guard.family

/**
 * 坐标系转换工具：
 *  - 服务端/老人端 GPS 上报均为 WGS-84
 *  - 高德原生地图与瓦片地图显示均为 GCJ-02（火星坐标）
 *  - 在地图上选点后必须 gcj2wgs 反纠偏再入库，否则围栏半径判断会有 50~500m 偏移
 */
object GeoConverter {

    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02 */
    fun wgs2gcj(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
        if (outOfChina(wgsLat, wgsLng)) return Pair(wgsLat, wgsLng)
        var dLat = transformLat(wgsLng - 105, wgsLat - 35)
        var dLng = transformLng(wgsLng - 105, wgsLat - 35)
        val radLat = wgsLat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - 0.00669342162296594323 * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((6378137 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (6378137 / sqrtMagic * Math.cos(radLat) * Math.PI)
        return Pair(wgsLat + dLat, wgsLng + dLng)
    }

    /** GCJ-02 → WGS-84（迭代反解，3 次迭代误差 < 1 米，足够围栏场景） */
    fun gcj2wgs(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (outOfChina(gcjLat, gcjLng)) return Pair(gcjLat, gcjLng)
        var wgsLat = gcjLat
        var wgsLng = gcjLng
        repeat(3) {
            val tmp = wgs2gcj(wgsLat, wgsLng)
            wgsLat += gcjLat - tmp.first
            wgsLng += gcjLng - tmp.second
        }
        return Pair(wgsLat, wgsLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
