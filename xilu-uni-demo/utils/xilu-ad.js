/** XiluAD 统一入口：配置 + 常量 + requireNativePlugin 封装 */

/** 应用 ID */
export const APP_ID = 'n7v99s3c'

/** 广告位 ID */
export const POS = {
    splash: 'ek96tfdg',
    banner: 'uk4jsu3p',
    reward: 'xgpvcpp3',
    interstitial: '9hmeh3af',
    nativeTemplate: '675bmzvg',
    nativeSelfRender: '59jmkybj'
}

/** 开屏 */
export const SPLASH = {
    /** 0 沉浸全屏 / 1 全屏 / 2 半屏 */
    IMMERSIVE_AND_FULLSCREEN: 0,
    FULL_SCREEN: 1,
    HALF_SCREEN: 2,
    LOAD_AND_SHOW: 0,
    LOAD_ONLY: 1,
    ONLY_SUPPORT_PLATFORM: null,
    CUSTOM_SKIP_VIEW: false,
    SKIP_VIEW_TIME_MS: 5000,
    LOGO_HEIGHT_PX: 0,
    AD_SHAKE_DISABLE: false,
    DEFAULT_TYPE: 0
}

/** Banner */
export const BANNER = {
    AUTO_REFRESH_INTERVAL: 30,
    ONLY_SUPPORT_PLATFORM: null,
    SCENE_ID: '',
    // 请求框（"宽*高"，px）：现在只当"槽位宽度 + 槽位高度上限"，横幅高度由平台渲染结果决定，
    // 留空即可（走后台配置，后台没配则兜底 屏宽×340dp 当上限）。
    AD_SIZE: '',
    AD_SHAKE_DISABLE: false
}

/** 信息流（模板 + 自渲染共用） */
export const NATIVE = {
    COUNT_SINGLE: 1,
    COUNT_LIST: 1,
    PLAY_WITH_MUTE: true,
    ONLY_SUPPORT_PLATFORM: null,
    SCENE_ID: ''
}

/** 激励视频 */
export const REWARD = {
    PLAY_WITH_MUTE: false,
    ONLY_SUPPORT_PLATFORM: null,
    SCENE_ID: '',
    AD_SHAKE_DISABLE: false
}

/** 插屏 */
export const INTERSTITIAL = {
    PLAY_WITH_MUTE: false,
    ONLY_SUPPORT_PLATFORM: null,
    SCENE_ID: ''
}

/** SDK 初始化参数 */
export const INIT = {
    appId: APP_ID,
    // 渠道定向（onlySupportPlatform）只在 debug=true 时生效，正式包保持 false
    debug: false,
    // SDK 日志开关（tag = ADXiluLog），正式包保持 false
    logDebug: false,
    agreePrivacyStrategy: true,
    isCanUseLocation: true,
    isCanReadInstallList: true,
    isCanUseReadWriteExternal: false,
    isCanUseWifiState: true,
    filterThirdQuestion: true,
    isCanUseSensor: true,
    customDeviceInfo: {}
}

/** 把插件回传的 error 转成可直接展示的字符串（对象或 JSON 字符串都兼容） */
export function adErrorText(error) {
    if (error == null) return ''
    if (typeof error === 'string') return error
    try {
        return JSON.stringify(error)
    } catch (e) {
        return String(e)
    }
}

/** 加载/渲染失败是否弹 toast；关掉后原因仍会进 console */
export const AD_TOAST = {
    ERROR: true
}

/** 统一的广告失败处理：按 AD_TOAST.ERROR 弹 toast 并打印原因，返回错误文本；兼容 detail/data 两种回调形状 */
export function handleAdFailed(tag, e) {
    const box = (e && (e.detail || e.data)) || {}
    // errorText 是插件给的纯文本；error 是 SDK 原始 JSON，作为兜底
    const text = adErrorText(box.errorText || box.error || '')
    console.log('[xilu-ad] ' + tag + ' failed:', text)
    if (AD_TOAST.ERROR) {
        uni.showToast({ title: text, icon: 'none' })
    }
    return text
}

let ad = null
let tip = '广告插件仅支持 App 端'
// #ifdef APP-PLUS
try {
    ad = uni.requireNativePlugin('Xilu-AD')
} catch (e) {
    ad = null
}
if (!ad) tip = '未检测到 Xilu-AD 插件，请先制作自定义调试基座'
// #endif

/** 插件缺失时按统一的失败形状回调，避免各页面各自判空 */
function proxy(name) {
    return (o, cb) => {
        if (!ad) {
            console.log('[xilu-ad] ' + tip)
            if (AD_TOAST.ERROR) {
                uni.showToast({ title: tip, icon: 'none' })
            }
            if (cb) cb({ event: 'onFailed', data: { error: tip } })
            return
        }
        ad[name](o, cb)
    }
}

export const init = proxy('init')
export const loadRewardVideo = proxy('loadRewardVideo')
export const showRewardVideo = proxy('showRewardVideo')
export const loadInterstitial = proxy('loadInterstitial')
export const showInterstitial = proxy('showInterstitial')
export const showSplash = proxy('showSplash')

export function isInit() {
    return !!ad && ad.isInit()
}
