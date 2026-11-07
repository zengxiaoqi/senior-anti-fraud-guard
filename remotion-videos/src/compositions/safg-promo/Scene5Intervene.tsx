// 场景 5 · 干预（810-1050帧 / 27-35s）：子女端真实控制台 + 高危告警推送 + 远程打断
import { AbsoluteFill, Img, staticFile, useCurrentFrame } from "remotion";
import {
  C,
  FONT,
  PhoneFrame,
  SceneTitle,
  clampI,
  mkSpring,
  FPS,
  pulse,
  sceneExit,
} from "./shared";

export const Scene5Intervene: React.FC = () => {
  const frame = useCurrentFrame();
  const total = 240;

  const phoneS = mkSpring(frame, FPS, 6, { damping: 120, stiffness: 160 });
  const phoneY = (1 - phoneS) * 700;

  // 告警推送从顶部落下
  const pushS = mkSpring(frame, FPS, 46, { damping: 90, stiffness: 260 });
  const pushY = (1 - pushS) * -520;

  // 红色覆屏脉冲（模拟远程打断覆屏）
  const overlayP = pulse(frame, 30);

  // 底部按钮脉冲
  const btnP = pulse(frame, 22);

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      <SceneTitle
        badge="② 干预"
        title="洗脑现场\n一键叫停"
        sub="强提醒穿透静音，红色覆屏 + 预录语音"
        color={C.red}
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
        <PhoneFrame glow={C.red}>
          <Img
            src={staticFile("assets/family-console.png")}
            style={{ width: "100%", display: "block" }}
          />
          {/* 红色覆屏脉冲 */}
          <AbsoluteFill
            style={{
              background: `radial-gradient(circle at 50% 40%, ${C.red}66 0%, ${C.redDeep}CC 100%)`,
              opacity: clampI(frame, 60, 90, 0, 1) * (0.25 + overlayP * 0.3),
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 30,
            }}
          >
            <div style={{ fontSize: 110 }}>🚨</div>
            <div
              style={{
                fontFamily: FONT,
                fontSize: 62,
                fontWeight: 900,
                color: "#fff",
                textShadow: "0 6px 30px rgba(0,0,0,0.6)",
              }}
            >
              远程亲情打断
            </div>
            <div
              style={{
                fontFamily: FONT,
                fontSize: 34,
                fontWeight: 600,
                color: "#FFD9DF",
              }}
            >
              子女语音 · 正在喊话
            </div>
          </AbsoluteFill>
        </PhoneFrame>
      </div>

      {/* 告警推送卡（右侧落下） */}
      <div
        style={{
          position: "absolute",
          top: 500,
          right: 56,
          width: 400,
          transform: `translateY(${pushY}px)`,
          opacity: clampI(frame, 46, 60, 0, 1),
        }}
      >
        <div
          style={{
            padding: "34px 34px 30px",
            borderRadius: 32,
            background: "linear-gradient(150deg,#2A1020,#3D0D1E)",
            border: `3px solid ${C.red}`,
            boxShadow: `0 0 ${30 + btnP * 50}px ${C.red}77, 0 24px 70px rgba(0,0,0,0.6)`,
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 16,
              marginBottom: 20,
            }}
          >
            <div
              style={{
                width: 64,
                height: 64,
                borderRadius: 32,
                background: C.red,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontSize: 34,
                boxShadow: `0 0 ${14 + btnP * 26}px ${C.red}`,
              }}
            >
              🔔
            </div>
            <div>
              <div
                style={{
                  fontFamily: FONT,
                  fontSize: 40,
                  fontWeight: 900,
                  color: "#fff",
                }}
              >
                高危告警
              </div>
              <div
                style={{
                  fontFamily: FONT,
                  fontSize: 24,
                  fontWeight: 600,
                  color: "#FF8FA3",
                }}
              >
                长者防诈守护 · 现在
              </div>
            </div>
          </div>
          <div
            style={{
              fontFamily: FONT,
              fontSize: 30,
              fontWeight: 600,
              color: C.text,
              lineHeight: 1.5,
            }}
          >
            检测到异常通话
            <br />
            <span style={{ color: "#FF8FA3" }}>陌生号码已持续 32 分钟</span>
          </div>
          <div
            style={{
              marginTop: 22,
              padding: "16px 0 0",
              borderTop: `2px solid ${C.red}55`,
              fontFamily: FONT,
              fontSize: 28,
              fontWeight: 800,
              color: C.red,
            }}
          >
            建议立即远程打断 →
          </div>
        </div>

        {/* 一键喊话按钮 */}
        <div
          style={{
            marginTop: 30,
            padding: "26px 0",
            borderRadius: 999,
            background: C.red,
            textAlign: "center",
            fontFamily: FONT,
            fontSize: 40,
            fontWeight: 900,
            color: "#fff",
            letterSpacing: 4,
            boxShadow: `0 0 ${20 + btnP * 50}px ${C.red}99`,
            transform: `scale(${1 + btnP * 0.03})`,
          }}
        >
          📣 一键喊话打断
        </div>
      </div>

      {/* 底部说明 */}
      <div
        style={{
          position: "absolute",
          bottom: 110,
          left: 0,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          gap: 24,
          opacity: clampI(frame, 150, 175, 0, 1),
          transform: `translateY(${clampI(frame, 150, 175, 30, 0)}px)`,
        }}
      >
        {["强提醒 · 穿透静音", "红色覆屏 · 强制打断", "预录语音 · 即时播放"].map((t) => (
          <div
            key={t}
            style={{
              padding: "14px 28px",
              borderRadius: 16,
              background: `${C.red}14`,
              border: `2px solid ${C.red}55`,
              fontFamily: FONT,
              fontSize: 28,
              fontWeight: 700,
              color: "#FFB3C1",
            }}
          >
            {t}
          </div>
        ))}
      </div>

      <AbsoluteFill
        style={{ opacity: sceneExit(frame, total, 12).opacity, pointerEvents: "none" }}
      />
    </AbsoluteFill>
  );
};
