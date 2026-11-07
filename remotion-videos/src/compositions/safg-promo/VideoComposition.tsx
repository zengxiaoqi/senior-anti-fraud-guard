// 主组合：长者防诈守护 · 推广视频（52s / 1560帧 @30fps，1080x1920 竖屏）
import { AbsoluteFill, Sequence } from "remotion";
import { Scene1Hook } from "./Scene1Hook";
import { Scene2Frauds } from "./Scene2Frauds";
import { Scene3Brand } from "./Scene3Brand";
import { Scene4Sense } from "./Scene4Sense";
import { Scene5Intervene } from "./Scene5Intervene";
import { Scene6Evidence } from "./Scene6Evidence";
import { Scene7CTA } from "./Scene7CTA";

export const SafgPromoComposition: React.FC = () => {
  return (
    <AbsoluteFill style={{ background: "#0A0E17" }}>
      {/* 1 · Hook 红色警报（0-4s） */}
      <Sequence from={0} durationInFrames={120}>
        <Scene1Hook />
      </Sequence>
      {/* 2 · 六大骗局快闪（4-13s） */}
      <Sequence from={120} durationInFrames={270}>
        <Scene2Frauds />
      </Sequence>
      {/* 3 · 品牌亮相（13-19s） */}
      <Sequence from={390} durationInFrames={180}>
        <Scene3Brand />
      </Sequence>
      {/* 4 · 感知（19-27s） */}
      <Sequence from={570} durationInFrames={240}>
        <Scene4Sense />
      </Sequence>
      {/* 5 · 干预（27-35s） */}
      <Sequence from={810} durationInFrames={240}>
        <Scene5Intervene />
      </Sequence>
      {/* 6 · 存证（35-42s） */}
      <Sequence from={1050} durationInFrames={210}>
        <Scene6Evidence />
      </Sequence>
      {/* 7 · CTA 双入口（42-52s） */}
      <Sequence from={1260} durationInFrames={300}>
        <Scene7CTA />
      </Sequence>
    </AbsoluteFill>
  );
};
