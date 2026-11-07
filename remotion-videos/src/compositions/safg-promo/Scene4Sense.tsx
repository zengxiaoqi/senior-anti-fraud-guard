// 场景 4 · 感知（570-810帧 / 19-27s）：老人端真实首页 + 四大感知能力标注
import { AbsoluteFill, Img, staticFile, useCurrentFrame } from "remotion";
import {
  C,
  FONT,
  PhoneFrame,
  SceneTitle,
  clampI,
  mkSpring,
  FPS,
  sceneExit,
} from "./shared";

const FEATS = [
  { icon: "📞", title: "异常通话", desc: "长时通话实时监测", color: C.blue },
  { icon: "📍", title: "敏感地点", desc: "电子围栏停留感知", color: C.purple },
  { icon: "💳", title: "异常扣款", desc: "通知栏支付捕获", color: C.amber },
  { icon: "🛡️", title: "跌倒检测", desc: "分段场景录音存证", color: C.green },
];

export const Scene4Sense: React.FC = () => {
  const frame = useCurrentFrame();
  const total = 240;

  // 手机从底部弹入
  const phoneS = mkSpring(frame, FPS, 6, { damping: 120, stiffness: 160 });
  const phoneY = (1 - phoneS) * 700;

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      <SceneTitle
        badge="① 感知"
        title="风险还没发生\n它先知道"
        sub="老人端无感运行，异常即刻记录"
        color={C.blue}
        frame={frame}
        top={120}
      />

      {/* 手机 mockup（真实截图） */}
      <div
        style={{
          position: "absolute",
          top: 470,
          left: 66,
          width: 560,
          transform: `translateY(${phoneY}px)`,
          opacity: clampI(frame, 6, 20, 0, 1),
        }}
      >
        <PhoneFrame glow={C.blue}>
          <Img
            src={staticFile("assets/elder-home.png")}
            style={{ width: "100%", display: "block" }}
          />
        </PhoneFrame>
      </div>

      {/* 右侧能力标注 */}
      <div style={{ position: "absolute", top: 520, right: 56, width: 400 }}>
        {FEATS.map((f, i) => {
          const s = mkSpring(frame, FPS, 40 + i * 22, { damping: 60, stiffness: 300 });
          const op = clampI(frame, 40 + i * 22, 54 + i * 22, 0, 1);
          return (
            <div
              key={f.title}
              style={{
                marginBottom: 34,
                padding: "26px 30px",
                borderRadius: 28,
                background: C.bgCard,
                border: `2px solid ${f.color}55`,
                boxShadow: `0 16px 50px rgba(0,0,0,0.5)`,
                transform: `translateX(${(1 - s) * 260}px)`,
                opacity: op,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
                <div
                  style={{
                    width: 76,
                    height: 76,
                    borderRadius: 22,
                    background: `${f.color}22`,
                    border: `2px solid ${f.color}`,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    fontSize: 40,
                  }}
                >
                  {f.icon}
                </div>
                <div>
                  <div
                    style={{
                      fontFamily: FONT,
                      fontSize: 42,
                      fontWeight: 900,
                      color: C.text,
                    }}
                  >
                    {f.title}
                  </div>
                  <div
                    style={{
                      fontFamily: FONT,
                      fontSize: 26,
                      fontWeight: 500,
                      color: C.textDim,
                      marginTop: 4,
                    }}
                  >
                    {f.desc}
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* 底部状态条 */}
      <div
        style={{
          position: "absolute",
          bottom: 120,
          left: 0,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          opacity: clampI(frame, 150, 175, 0, 1),
          transform: `translateY(${clampI(frame, 150, 175, 30, 0)}px)`,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 16,
            padding: "16px 40px",
            borderRadius: 999,
            background: `${C.green}1A`,
            border: `2px solid ${C.green}`,
            fontFamily: FONT,
            fontSize: 34,
            fontWeight: 800,
            color: C.green,
          }}
        >
          ✓ 守护运行中 · 电话 / 扣款 / 位置全程保护
        </div>
      </div>

      <AbsoluteFill
        style={{ opacity: sceneExit(frame, total, 12).opacity, pointerEvents: "none" }}
      />
    </AbsoluteFill>
  );
};
