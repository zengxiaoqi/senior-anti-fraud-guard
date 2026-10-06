package com.antifraud.guard.family

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.antifraud.guard.R
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.view.TrackView

/**
 * 亲情轨迹：老人端上报位置轨迹可视化
 * 对齐小程序 pages/map/map（地图组件改为内置轻量轨迹画布，无需地图 SDK）
 */
class TrackFragment : Fragment() {

    private var lastFetch = 0L

    private lateinit var trackView: TrackView
    private lateinit var tvTrackList: TextView
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_family_track, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        trackView = view.findViewById(R.id.track_view)
        tvTrackList = view.findViewById(R.id.tv_track_list)
        tvEmpty = view.findViewById(R.id.tv_track_empty)
    }

    override fun onResume() {
        super.onResume()
        fetchLocations()
    }

    /** 30s 节流（对齐小程序） */
    private fun fetchLocations() {
        if (!GuardConfig.isFamilyBound) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "尚未绑定老人\n请先到「控制台」完成亲情绑定"
            trackView.visibility = View.GONE
            tvTrackList.text = ""
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastFetch < 30_000) return
        lastFetch = now

        ApiClient.familyGet("/api/events/location/${GuardConfig.boundElderId}",
            onSuccess = { data ->
                val arr = data.optJSONArray("data")
                val points = mutableListOf<TrackView.Point>()
                val sb = StringBuilder("轨迹明细（新→旧）：\n")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val loc = arr.optJSONObject(i) ?: continue
                        val lat = loc.optDouble("latitude", 0.0)
                        val lng = loc.optDouble("longitude", 0.0)
                        if (lat == 0.0 || lng == 0.0) continue
                        points.add(TrackView.Point(lat, lng, loc.optString("address", "")))
                        val time = loc.optString("created_at", "").replace("T", " ").take(16)
                        sb.append("\n• ").append(loc.optString("address", "未知位置"))
                            .append("（").append(time).append("）")
                    }
                }
                if (points.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "暂无轨迹数据\n老人端开启位置守护后自动上报"
                    trackView.visibility = View.GONE
                    tvTrackList.text = ""
                } else {
                    tvEmpty.visibility = View.GONE
                    trackView.visibility = View.VISIBLE
                    trackView.setPoints(points)
                    tvTrackList.text = sb.toString()
                }
            },
            onError = {
                Toast.makeText(context, "获取位置信息失败：$it", Toast.LENGTH_SHORT).show()
            })
    }
}
