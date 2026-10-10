// Capacitor 插件的**环境声明**
// ---------------------------------------------------------------------------
// 手机打包用的 @capacitor/core 和 @capacitor/filesystem 现在还没有安装
// （装了会给项目增加几十个依赖，而且现在还没到打包那一步）。
//
// 但我们要写的 persistFile.ts 引用了它们。为了让类型检查能过、又不必现在就
// 安装依赖，这里用 `declare module` 声明出我们实际用到的那几个 API。
//
// 等真正打包时执行 `npm i @capacitor/core @capacitor/filesystem`，
// 官方自带的类型会**自动覆盖**这里的声明（node_modules 里的类型优先），
// 所以这个文件到时候可以删掉，也可以留着不管。

declare module '@capacitor/core' {
  export const Capacitor: {
    /** 是否运行在原生 App 里（浏览器里是 false） */
    isNativePlatform(): boolean;
    getPlatform(): string;
  };
}

declare module '@capacitor/filesystem' {
  /** 存储位置。Data = App 私有目录 */
  export const Directory: {
    Data: string;
    Documents: string;
    Cache: string;
    ExternalStorage: string;
  };

  export interface WriteFileOptions {
    path: string;
    data: string;
    directory?: string;
    encoding?: string;
    recursive?: boolean;
  }
  export interface ReadFileOptions {
    path: string;
    directory?: string;
    encoding?: string;
  }
  export interface ReadFileResult {
    data: string;
  }
  export interface ReaddirResult {
    files: Array<{ name: string; size?: number; mtime?: number } | string>;
  }

  export const Filesystem: {
    writeFile(options: WriteFileOptions): Promise<{ uri: string }>;
    readFile(options: ReadFileOptions): Promise<ReadFileResult>;
    mkdir(options: { path: string; directory?: string; recursive?: boolean }): Promise<void>;
    readdir(options: { path: string; directory?: string }): Promise<ReaddirResult>;
    deleteFile(options: { path: string; directory?: string }): Promise<void>;
    stat(options: { path: string; directory?: string }): Promise<{ size: number; mtime: number }>;
  };
}
