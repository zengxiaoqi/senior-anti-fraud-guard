// ============ EDITABLE CONSTANTS · SAFG PROMO ============
// 长者防诈守护 · 推广视频共享设计系统
import { interpolate, spring } from "remotion";

export const FPS = 30;
export const W = 1080;
export const H = 1920;

// 配色：从产品真实界面提取（SOS红 / AI紫 / 守护蓝 / 安全绿 + 深蓝黑夜底）
export const C = {
  bg: "#0A0E17",
  bgCard: "#141B2E",
  bgCard2: "#1A2340",
  red: "#FF2D55",
  redDeep: "#8C0E2E",
  purple: "#8B5CF6",
  blue: "#3B82F6",
  green: "#22C55E",
  amber: "#F59E0B",
  text: "#F8FAFC",
  textDim: "#8B98B3",
  line: "#26304A",
};

export const FONT =
  '"Noto Sans SC","Microsoft YaHei","PingFang SC","Source Han Sans SC",sans-serif';

// ============ 动画工具 ============
export const clampI = (
  frame: number,
  a: number,
  b: number,
  c: number,
  d: number,
) =>
  interpolate(frame, [a, b], [c, d], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

// 淡入 + 上浮（最常用入场）
export const fadeSlideUp = (frame: number, start: number, dur = 18, dist = 40) => ({
  opacity: clampI(frame, start, start + dur, 0, 1),
  transform: `translateY(${clampI(frame, start, start + dur + 8, dist, 0)}px)`,
});

// 场景退出：整体淡出 + 轻微放大
export const sceneExit = (frame: number, total: number, dur = 14) => ({
  opacity: clampI(frame, total - dur, total, 1, 0),
});

// 警报脉冲（0..1）
export const pulse = (frame: number, period = 26, phase = 0) =>
  0.5 + 0.5 * Math.sin((frame / period) * Math.PI * 2 + phase);

export const SPRING_SNAP = { damping: 200, stiffness: 400, mass: 1 };
export const SPRING_BOUNCE = { damping: 50, stiffness: 300, mass: 1 };
export const SPRING_SOFT = { damping: 100, stiffness: 200, mass: 1 };

export const mkSpring = (
  frame: number,
  fps: number,
  delay = 0,
  config = SPRING_SNAP,
) =>
  spring({
    frame: frame - delay,
    fps,
    config,
    durationInFrames: 60,
  });

// 打字机
export const typeText = (frame: number, text: string, start: number, speed = 2) =>
  text.slice(0, Math.max(0, Math.floor((frame - start) / speed)));

// ============ 通用视觉组件 ============
export const AlertLamps: React.FC<{ frame: number; color?: string }> = ({
  frame,
  color = C.red,
}) => {
  const corners = [
    { top: -260, left: -260 },
    { top: -260, right: -260 },
    { bottom: -260, left: -260 },
    { bottom: -260, right: -260 },
  ];
  return (
    <>
      {corners.map((pos, i) => {
        const p = pulse(frame, 24, i * 1.6);
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              width: 620,
              height: 620,
              borderRadius: 320,
              background: `radial-gradient(circle, ${color}66 0%, transparent 65%)`,
              opacity: 0.25 + p * 0.75,
              ...pos,
            }}
          />
        );
      })}
    </>
  );
};

// 故障风标题（RGB 分离）
export const GlitchText: React.FC<{
  text: string;
  size: number;
  color?: string;
  weight?: number;
  frame?: number;
  style?: React.CSSProperties;
}> = ({ text, size, color = C.text, weight = 900, frame = 0, style }) => {
  const jitterX = Math.sin(frame * 0.9) * 1.5 + (frame % 17 === 0 ? 5 : 0);
  const jitterY = Math.cos(frame * 1.3) * 1.2;
  return (
    <div style={{ position: "relative", display: "inline-block", ...style }}>
      <span
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          fontFamily: FONT,
          fontSize: size,
          fontWeight: weight,
          color: C.red,
          transform: `translate(${jitterX + 4}px, ${jitterY}px)`,
          mixBlendMode: "screen",
          whiteSpace: "pre",
        }}
      >
        {text}
      </span>
      <span
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          fontFamily: FONT,
          fontSize: size,
          fontWeight: weight,
          color: C.blue,
          transform: `translate(${-jitterX - 4}px, ${-jitterY}px)`,
          mixBlendMode: "screen",
          whiteSpace: "pre",
        }}
      >
        {text}
      </span>
      <span
        style={{
          position: "relative",
          fontFamily: FONT,
          fontSize: size,
          fontWeight: weight,
          color,
          whiteSpace: "pre",
        }}
      >
        {text}
      </span>
    </div>
  );
};

// 手机 mockup 框
export const PhoneFrame: React.FC<{
  children: React.ReactNode;
  glow?: string;
  style?: React.CSSProperties;
}> = ({ children, glow = C.blue, style }) => (
  <div
    style={{
      position: "relative",
      borderRadius: 56,
      padding: 12,
      background: "linear-gradient(160deg,#2A3555,#0D1322 60%)",
      boxShadow: `0 0 90px ${glow}44, 0 30px 80px rgba(0,0,0,0.7), inset 0 0 0 2px #3A4A75`,
      ...style,
    }}
  >
    <div
      style={{
        borderRadius: 46,
        overflow: "hidden",
        position: "relative",
        background: C.bg,
      }}
    >
      {children}
    </div>
  </div>
);

// 章节标题（序号胶囊 + 主标题 + 副注）
export const SceneTitle: React.FC<{
  badge: string;
  title: string;
  sub?: string;
  color?: string;
  frame?: number;
  top?: number;
}> = ({ badge, title, sub, color = C.red, frame = 0, top = 150 }) => (
  <div style={{ position: "absolute", top, left: 0, width: "100%", padding: "0 64px" }}>
    <div
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 14,
        padding: "10px 26px",
        borderRadius: 999,
        background: `${color}22`,
        border: `2px solid ${color}`,
        color,
        fontFamily: FONT,
        fontSize: 30,
        fontWeight: 800,
        letterSpacing: 4,
        opacity: clampI(frame, 0, 14, 0, 1),
        transform: `translateY(${clampI(frame, 0, 14, -24, 0)}px)`,
      }}
    >
      {badge}
    </div>
        <div
          style={{
            marginTop: 26,
            fontFamily: FONT,
            fontSize: 74,
            fontWeight: 900,
            color: C.text,
            lineHeight: 1.15,
            whiteSpace: "pre-line",
            opacity: clampI(frame, 8, 22, 0, 1),
            transform: `translateY(${clampI(frame, 8, 22, 36, 0)}px)`,
          }}
        >
      {title.split("\\n").join("\n")}
    </div>
    {sub ? (
      <div
        style={{
          marginTop: 18,
          fontFamily: FONT,
          fontSize: 32,
          fontWeight: 500,
          color: C.textDim,
          opacity: clampI(frame, 16, 28, 0, 1),
        }}
      >
        {sub}
      </div>
    ) : null}
  </div>
);

// 心跳波形线
export const HeartbeatLine: React.FC<{ color?: string; top?: number }> = ({
  color = C.red,
  top = 1720,
}) => (
  <svg
    viewBox="0 0 1080 120"
    style={{ position: "absolute", top, left: 0, width: "100%", height: 120 }}
  >
    <path
      d="M0,60 L240,60 L280,60 L300,20 L320,100 L340,10 L360,90 L380,60 L560,60 L600,60 L620,20 L640,100 L660,10 L680,90 L700,60 L1080,60"
      fill="none"
      stroke={color}
      strokeWidth={4}
      opacity={0.9}
      strokeLinecap="round"
    />
  </svg>
);
