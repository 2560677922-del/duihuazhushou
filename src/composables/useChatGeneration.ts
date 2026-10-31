// 一次「聊天生成」的全局状态
// ---------------------------------------------------------------------------
// 为什么必须放在**模块级**，而不是 Adventure.vue 里的局部 ref：
//
//   用户点发送 → 切到「资产」页看东西 → 再切回聊天。
//   这一来一回，Adventure.vue 已经被卸载重建，局部 ref 全没了。
//   但底下的 fetch 其实还在跑、草稿也还在往盘里写 ——
//   界面却变成一片空白，用户完全不知道发生了什么，
//   还会以为"卡住了"，于是又点一次发送，两条请求叠加。
//
//   放到模块级之后：
//     · 回到聊天页能立刻看到「还在生成中」和已经流出来的正文；
//     · 「停止」按钮对新组件实例依然有效（AbortController 也在这里）。
//
// 注意：这里只放**状态**，不放业务逻辑。
//   组装提示词、写草稿、落库仍然在 Adventure.vue 的 sendAction 里 ——
//   那些逻辑跟当前会话、当前预设强相关，搬过来反而更难读。
import { ref, type Ref } from 'vue';

/** 是否正在生成（跨页面保持） */
const streaming = ref(false);
/** 正在为哪个会话生成。切到别的会话时不该显示"生成中" */
const sessionId = ref<string | null>(null);
/** 这次生成对应的用户输入，流式期间界面上要显示 */
const userText = ref('');
/** 已经流出来的正文（跨页面保持，回来还能接着看） */
const output = ref('');
/** 开始时间，用来显示"已经等了多久" */
const startedAt = ref(0);
/** 用户是不是主动点了「停止」。用来区分"被中止"和"真出错" */
const stopped = ref(false);

let controller: AbortController | null = null;

export interface ChatGeneration {
  streaming: Ref<boolean>;
  sessionId: Ref<string | null>;
  userText: Ref<string>;
  output: Ref<string>;
  startedAt: Ref<number>;
  stopped: Ref<boolean>;
  /** 开始一次生成，返回要传给 fetch 的 signal */
  begin: (sid: string | null, user: string) => AbortSignal;
  /** 用户点了「停止」 */
  stop: () => void;
  /** 结束（成功、失败、中止都要调） */
  finish: () => void;
}

export function useChatGeneration(): ChatGeneration {
  function begin(sid: string | null, user: string): AbortSignal {
    // 理论上不会走到这儿；真出现说明上一次没收干净，先把它掐掉，
    // 免得两个请求同时往同一个 streamOutput 里写（正文会串行交错）。
    if (controller) {
      try {
        controller.abort();
      } catch {
        /* ignore */
      }
    }
    controller = new AbortController();
    streaming.value = true;
    stopped.value = false;
    sessionId.value = sid;
    userText.value = user;
    output.value = '';
    startedAt.value = Date.now();
    return controller.signal;
  }

  function stop(): void {
    if (!controller) return;
    stopped.value = true;
    try {
      controller.abort();
    } catch {
      /* ignore */
    }
  }

  function finish(): void {
    streaming.value = false;
    sessionId.value = null;
    userText.value = '';
    output.value = '';
    startedAt.value = 0;
    controller = null;
    stopped.value = false;
  }

  return { streaming, sessionId, userText, output, startedAt, stopped, begin, stop, finish };
}
