/** 迁移期占位：还没实现的接口一律 501，方便灰度期一眼区分"没迁移"和"迁移了但报错" */
export function notImplemented(pathname: string): Response {
  return Response.json(
    {
      success: false,
      error: '该接口尚未迁移到 Workers 后端（迁移进行中）',
      path: pathname
    },
    { status: 501 }
  );
}
