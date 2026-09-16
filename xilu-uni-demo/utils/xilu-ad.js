/**
 * XiluAD 统一入口：配置 + 常量 + requireNativePlugin 封装
 * 页面只需 import { init, showSplash, POS, SPLASH, ... } from '@/utils/xilu-ad'
 */

// ── 配置 ──────────────────────────────────────────────

/** 应用 ID */
export const APP_ID = 'n7v99s3c'

/** 广告位 ID */
export const POS = {
    splash: 'ek96tfdg',
    banner: 'uk4jsu3p',
    reward: 'xgpvcpp3',
    interstitial: '9hmeh3af',
    fullscreen: '6620c9299df9013cf5',
    nativeTemplate: '675bmzvg',
    nativeSelfRender: '59jmkybj',
    draw: '4d6bee2ec7217adf86'
}

/** 开屏 */
export const SPLASH = {
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

/** 全屏视频 */
export const FULL_SCREEN = {
    ONLY_SUPPORT_PLATFORM: null
}

/** Draw 视频信息流 */
export const DRAW = {
    COUNT: 1,
    ONLY_SUPPORT_PLATFORM: null
}

/** SDK 初始化参数 */
export const INIT = {
    appId: APP_ID,
    debug: false,
    // Xilu SDK 的日志开关（tag = ADXiluLog）。SDK 内部从不调用 setEnableLog，
    // 且 AAR 以 release 编译把 BuildConfig.DEBUG 内联成了 false，所以必须由宿主打开。
    // 正式包保持 false；排查竞价问题时改成 true。
    logDebug: false,
    agreePrivacyStrategy: false,
    isCanUseLocation: true,
    isCanUsePhoneState: true,
    isCanReadInstallList: true,
    isCanUseReadWriteExternal: false,
    isCanUseWifiState: true,
    isCanUseOaid: true,
    filterThirdQuestion: true,
    isCanUseSensor: true,
    customDeviceInfo: {}
}

/**
 * 把插件回传的 error 转成可直接展示的字符串。
 * error 可能是对象（uni 已解析的 JSON），也可能是 JSON 字符串；SDK 原样回传什么就展示什么。
 */
export function adErrorText(error) {
    if (error == null) return ''
    if (typeof error === 'string') return error
    try {
        return JSON.stringify(error)
    } catch (e) {
        return String(e)
    }
}

// ── 提示开关 ──────────────────────────────────────────
//
// Demo 里为了方便观察，失败时会弹 toast。正式接入通常不希望打扰用户：
// 把 AD_TOAST.ERROR 改成 false 即可全局关闭（所有页面都走 handleAdFailed）。
// 关闭后失败原因仍会进 console，排查时改回 true 或看控制台。

export const AD_TOAST = {
    /** 广告加载/渲染失败是否弹 toast */
    ERROR: true
}

/**
 * 统一的广告失败处理：按开关决定是否弹 toast，并把原因写入控制台。
 *
 * 同时兼容两种回调形状：
 * - 原生组件事件：`{ type, detail: { error, errorText, errorCode } }`
 * - Module 回调：`{ event, data: { error } }`
 *
 * 页面统一这样用：
 *
 *   onAdFailed(e) {
 *       handleAdFailed('feed', e)   // 关掉 AD_TOAST.ERROR 后这里就不再弹提示
 *       this.show = false
 *   }
 *
 * @param {String} tag 日志前缀，便于区分是哪个页面
 * @param {Object} e   组件事件对象或 Module 回调对象
 * @returns {String}   错误文本，页面如需自行展示可直接使用
 */
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

// ── Native Plugin ─────────────────────────────────────

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

function proxy(name) {
    return (o, cb) => {
        if (!ad) {
            // 插件缺失属于集成错误，同样受开关控制（正式包不希望给用户看到）
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
export const loadFullScreenVod = proxy('loadFullScreenVod')
export const showFullScreenVod = proxy('showFullScreenVod')
export const showSplash = proxy('showSplash')
export function isInit() {
    return !!ad && ad.isInit()
}
