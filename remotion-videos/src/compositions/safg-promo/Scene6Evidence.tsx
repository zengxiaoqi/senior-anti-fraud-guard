// 场景 6 · 存证（1050-1260帧 / 35-42s）：四类证据飞入 → 合并为《报案维权证据包》
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT, SceneTitle, clampI, mkSpring, FPS, sceneExit } from "./shared";

const EVIDENCES = [
  { icon: "🎙️", title: "环境录音", desc: "案发现场声音留存", color: C.blue, dx: -320, dy: -300, rot: -14 },
  { icon: "🗺️", title: "位置轨迹", desc: "停留地点一目了然", color: C.purple, dx: 320, dy: -300, rot: 12 },
  { icon: "🧾", title: "扣款记录", desc: "异常支付自动捕获", color: C.amber, dx: -320, dy: 300, rot: 10 },
  { icon: "📝", title: "AI 转写", desc: "录音文字全文研判", color: C.green, dx: 320, dy: 300, rot: -12 },
];

export const Scene6Evidence: React.FC = () => {
  const frame = useCurrentFrame();
  const total = 210;

  // 合并时间线：飞入(0-50) → 吸附合并(50-95) → 封面呈现(95+)
  const merge = clampI(frame, 46, 92, 0, 1); // 0=散开 1=合并
  const coverS = mkSpring(frame, FPS, 92, { damping: 60, stiffness: 240 });

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      <SceneTitle
        badge="③ 存证"
        title="证据，一键打包"
        sub="录音 / 轨迹 / 扣款 / 转写，直接交给警察"
        color={C.amber}
        frame={frame}
        top={120}
      />

      {/* 四证卡 → 合并 */}
      <div
        style={{
          position: "absolute",
          top: 470,
          left: 0,
          width: "100%",
          height: 900,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        {EVIDENCES.map((e, i) => {
          const inS = mkSpring(frame, FPS, 8 + i * 8, { damping: 90, stiffness: 220 });
          const x = e.dx * (1 - inS) * (1 - merge);
          const y = e.dy * (1 - inS) * (1 - merge);
          const rot = e.rot * (1 - merge);
          const scale = 0.55 + 0.45 * inS;
          const fade = clampI(frame, 92, 108, 1, 0); // 合并后原卡淡出让位封面
          return (
            <div
              key={e.title}
              style={{
                position: "absolute",
                width: 380,
                padding: "36px 34px",
                borderRadius: 30,
                background: C.bgCard,
                border: `3px solid ${e.color}`,
                boxShadow: `0 20px 60px rgba(0,0,0,0.55), 0 0 40px ${e.color}33`,
                transform: `translate(${x}px, ${y}px) rotate(${rot}deg) scale(${scale * (1 - merge * 0.25)})`,
                opacity: inS * fade,
              }}
            >
              <div style={{ fontSize: 66 }}>{e.icon}</div>
              <div
                style={{
                  fontFamily: FONT,
                  fontSize: 46,
                  fontWeight: 900,
                  color: C.text,
                  marginTop: 14,
                }}
              >
                {e.title}
              </div>
              <div
                style={{
                  fontFamily: FONT,
                  fontSize: 27,
                  fontWeight: 500,
                  color: C.textDim,
                  marginTop: 8,
                }}
              >
                {e.desc}
              </div>
            </div>
          );
        })}

        {/* 证据包封面 */}
        <div
          style={{
            width: 560,
            padding: "60px 60px 54px",
            borderRadius: 44,
            background: "linear-gradient(150deg,#1E1420,#3D0D1E)",
            border: `4px solid ${C.amber}`,
            boxShadow: `0 0 80px ${C.amber}44, 0 30px 90px rgba(0,0,0,0.7)`,
            transform: `scale(${coverS})`,
            opacity: clampI(frame, 92, 104, 0, 1),
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 20,
              marginBottom: 40,
            }}
          >
            <div
              style={{
                padding: "10px 24px",
                borderRadius: 12,
                background: C.amber,
                fontFamily: FONT,
                fontSize: 28,
                fontWeight: 900,
                color: "#1A1206",
                letterSpacing: 3,
              }}
            >
              证据包
            </div>
            <div
              style={{
                fontFamily: FONT,
                fontSize: 26,
                fontWeight: 600,
                color: "#FCD34D",
              }}
            >
              EVIDENCE PACK
            </div>
          </div>
          <div
            style={{
              fontFamily: FONT,
              fontSize: 66,
              fontWeight: 900,
              color: C.text,
              lineHeight: 1.25,
            }}
          >
            报案维权
            <br />
            证据包
          </div>
          <div style={{ marginTop: 40 }}>
            {["✔ 通话录音与全文转写", "✔ 敏感地点轨迹证明", "✔ 异常扣款流水记录"].map((t, i) => (
              <div
                key={t}
                style={{
                  fontFamily: FONT,
                  fontSize: 32,
                  fontWeight: 600,
                  color: "#FDE68A",
                  marginBottom: 16,
                  opacity: clampI(frame, 100 + i * 8, 112 + i * 8, 0, 1),
                  transform: `translateX(${clampI(frame, 100 + i * 8, 112 + i * 8, -30, 0)}px)`,
                }}
              >
                {t}
              </div>
            ))}
          </div>
          <div
            style={{
              marginTop: 30,
              paddingTop: 26,
              borderTop: `2px solid ${C.amber}55`,
              fontFamily: FONT,
              fontSize: 30,
              fontWeight: 800,
              color: C.amber,
              opacity: clampI(frame, 128, 142, 0, 1),
            }}
          >
            一键生成 · 直接报案 →
          </div>
        </div>
      </div>

      <AbsoluteFill
        style={{ opacity: sceneExit(frame, total, 12).opacity, pointerEvents: "none" }}
      />
    </AbsoluteFill>
  );
};
