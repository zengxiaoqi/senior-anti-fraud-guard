// 场景 7 · CTA（1260-1560帧 / 42-52s）：双入口 + Slogan 收尾
import { AbsoluteFill, Img, staticFile, useCurrentFrame } from "remotion";
import { C, FONT, AlertLamps, clampI, mkSpring, FPS, pulse, sceneExit } from "./shared";

export const Scene7CTA: React.FC = () => {
  const frame = useCurrentFrame();
  const total = 300;
  const greenP = pulse(frame, 26);

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      <AlertLamps frame={frame} color={C.green} />

      {/* 绿色状态 */}
      <div
        style={{
          position: "absolute",
          top: 200,
          left: 0,
          width: "100%",
          display: "flex",
          justifyContent: "center",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 18,
            padding: "16px 42px",
            borderRadius: 999,
            background: `${C.green}1E`,
            border: `3px solid ${C.green}`,
            fontFamily: FONT,
            fontSize: 38,
            fontWeight: 900,
            color: C.green,
            boxShadow: `0 0 ${20 + greenP * 40}px ${C.green}55`,
            opacity: clampI(frame, 4, 18, 0, 1),
            transform: `scale(${clampI(frame, 4, 18, 0.7, 1)})`,
          }}
        >
          ✓ 一切正常，老人一切平安
        </div>
      </div>

      {/* 主标题 */}
      <div
        style={{
          position: "absolute",
          top: 360,
          left: 0,
          width: "100%",
          textAlign: "center",
          opacity: clampI(frame, 14, 32, 0, 1),
          transform: `translateY(${clampI(frame, 14, 32, 40, 0)}px)`,
        }}
      >
        <div
          style={{
            fontFamily: FONT,
            fontSize: 40,
            fontWeight: 800,
            color: C.green,
            letterSpacing: 8,
            marginBottom: 24,
          }}
        >
          守护，现在就能开始
        </div>
        <div
          style={{
            fontFamily: FONT,
            fontSize: 86,
            fontWeight: 900,
            color: C.text,
            lineHeight: 1.25,
          }}
        >
          把防线装进
          <br />
          爸妈的手机
        </div>
      </div>

      {/* 双入口卡 */}
      <div
        style={{
          position: "absolute",
          top: 900,
          left: 0,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          gap: 44,
        }}
      >
        {/* 小程序 */}
        <div
          style={{
            width: 400,
            padding: "40px 36px",
            borderRadius: 40,
            background: C.bgCard,
            border: `3px solid ${C.green}66`,
            boxShadow: "0 24px 70px rgba(0,0,0,0.55)",
            textAlign: "center",
            opacity: clampI(frame, 44, 62, 0, 1),
            transform: `translateY(${clampI(frame, 44, 62, 60, 0)}px)`,
          }}
        >
          <div
            style={{
              width: 220,
              height: 220,
              margin: "0 auto",
              borderRadius: 24,
              background: "#fff",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              padding: 12,
            }}
          >
            <Img
              src={staticFile("assets/miniprogram-qr.png")}
              style={{ width: "100%", display: "block" }}
            />
          </div>
          <div
            style={{
              marginTop: 28,
              fontFamily: FONT,
              fontSize: 44,
              fontWeight: 900,
              color: C.text,
            }}
          >
            微信小程序
          </div>
          <div
            style={{
              marginTop: 10,
              fontFamily: FONT,
              fontSize: 30,
              fontWeight: 600,
              color: C.green,
            }}
          >
            扫码即用 · 免安装
          </div>
        </div>

        {/* Android */}
        <div
          style={{
            width: 400,
            padding: "40px 36px",
            borderRadius: 40,
            background: C.bgCard,
            border: `3px solid ${C.blue}66`,
            boxShadow: "0 24px 70px rgba(0,0,0,0.55)",
            textAlign: "center",
            opacity: clampI(frame, 56, 74, 0, 1),
            transform: `translateY(${clampI(frame, 56, 74, 60, 0)}px)`,
          }}
        >
          <div
            style={{
              width: 220,
              height: 220,
              margin: "0 auto",
              borderRadius: 24,
              background: "#fff",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              padding: 12,
            }}
          >
            <Img
              src={staticFile("assets/apk-download-qr.png")}
              style={{ width: "100%", display: "block" }}
            />
          </div>
          <div
            style={{
              marginTop: 28,
              fontFamily: FONT,
              fontSize: 44,
              fontWeight: 900,
              color: C.text,
            }}
          >
            Android App
          </div>
          <div
            style={{
              marginTop: 10,
              fontFamily: FONT,
              fontSize: 27,
              fontWeight: 600,
              color: C.blue,
              lineHeight: 1.45,
            }}
          >
            扫码下载安装包
            <br />
            GitHub Releases
          </div>
        </div>
      </div>

      {/* Slogan 收尾 */}
      <div
        style={{
          position: "absolute",
          top: 1460,
          left: 0,
          width: "100%",
          textAlign: "center",
          opacity: clampI(frame, 110, 134, 0, 1),
          transform: `translateY(${clampI(frame, 110, 134, 50, 0)}px)`,
        }}
      >
        <div
          style={{
            fontFamily: FONT,
            fontSize: 74,
            fontWeight: 900,
            color: C.text,
            lineHeight: 1.35,
          }}
        >
          别等<span style={{ color: C.red }}>报警</span>了，
          <br />
          才装这道防线。
        </div>
        <div
          style={{
            marginTop: 40,
            fontFamily: FONT,
            fontSize: 30,
            fontWeight: 600,
            color: C.textDim,
            letterSpacing: 3,
            opacity: clampI(frame, 140, 160, 0, 1),
          }}
        >
          长者防诈守护 · 行为感知 → 实时干预 → 证据留存
        </div>
        <div
          style={{
            marginTop: 26,
            display: "inline-flex",
            alignItems: "center",
            gap: 14,
            padding: "14px 38px",
            borderRadius: 999,
            background: `${C.blue}14`,
            border: `2px solid ${C.blue}55`,
            fontFamily: "Menlo, Consolas, 'Courier New', monospace",
            fontSize: 33,
            fontWeight: 700,
            color: C.blue,
            letterSpacing: 1,
            opacity: clampI(frame, 152, 172, 0, 1),
            transform: `translateY(${clampI(frame, 152, 172, 24, 0)}px)`,
          }}
        >
          <svg width={34} height={34} viewBox="0 0 16 16" fill={C.blue}>
            <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
          </svg>
          github.com/zengxiaoqi/senior-anti-fraud-guard
        </div>
      </div>

      <AbsoluteFill
        style={{ opacity: sceneExit(frame, total, 16).opacity, pointerEvents: "none" }}
      />
    </AbsoluteFill>
  );
};
