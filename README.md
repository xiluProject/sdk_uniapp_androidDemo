# Xilu聚合广告SDK UniApp接入文档 V1.0.8.4



## 1. 概述

### 1.1 简介

尊敬的开发者朋友，欢迎您使用Xilu聚合广告SDK的UniApp版本。通过本文档，您可以快速完成SDK的集成。

**注意：本SDK仅支持Android平台（App端）**；H5和小程序端无法使用原生广告插件。



### 1.2 SDK组成结构

Xilu聚合广告SDK UniApp版本主要由**Xilu-AD原生插件**和**JS工具模块**组成：

* **Xilu-AD原生插件**：包含桥接层（XiluADUniPlugin）、核心SDK（XiluSDKCore）及各平台适配器，以UniApp原生插件形式集成
* **JS工具模块**：`utils/xilu-ad.js`，封装原生插件调用与常量配置，提供统一的JS API



### 1.3 支持的广告类型

<table>
  <tr>
    <th style="width:150px">类型</th>
    <th>简介</th>
    <th>页面类型</th>
  </tr>
  <tr>
    <td><a href="#ad_splash">开屏广告</a></td>
    <td>开屏广告以APP启动作为曝光时机，拉起独立原生Activity展示，提供5s可感知广告展示</td>
    <td>.vue</td>
  </tr>
  <tr>
    <td><a href="#ad_banner">横幅广告</a></td>
    <td>横幅广告是横向贯穿整个可视页面的模板广告，使用原生组件渲染</td>
    <td>.nvue</td>
  </tr>
  <tr>
    <td><a href="#ad_reward_vod">激励视频广告</a></td>
    <td>将短视频融入到APP场景当中，用户观看短视频广告后可以给予一些应用内奖励</td>
    <td>.vue</td>
  </tr>
  <tr>
    <td><a href="#ad_interstitial">插屏广告</a></td>
    <td>插屏广告是移动广告的一种常见形式，在应用流程中弹出，用户可以选择点击广告或将其关闭</td>
    <td>.vue</td>
  </tr>
  <tr>
    <td><a href="#ad_full_screen_vod">全屏视频广告</a></td>
    <td>类似激励视频，与激励视频不同的是，全屏视频广告在观看一定时长后即可跳过，没有激励回调</td>
    <td>.vue</td>
  </tr>
  <tr>
    <td><a href="#ad_native_express">信息流模板广告</a></td>
    <td>返回拼装好的广告视图，开发者只需将视图添加到相应容器即可，使用原生组件渲染</td>
    <td>.nvue</td>
  </tr>
  <tr>
    <td><a href="#ad_native_feed">信息流自渲染广告</a></td>
    <td>SDK将返回广告标题、描述、Icon、图片等信息，开发者自行拼装渲染</td>
    <td>.nvue</td>
  </tr>
  <tr>
    <td><a href="#ad_draw_vod">Draw视频信息流</a></td>
    <td>沉浸式视频流广告，用于类似抖音/快手的竖屏视频场景</td>
    <td>.nvue</td>
  </tr>
</table>



### 1.4 SDK容量说明

| 组件 | 大小 | 版本号 | 备注 |
|------|------|--------|------|
| XiluSDKCore（核心SDK） | 0.43M | V1.0.8.4 | 必须导入 |
| XiluADUniPlugin（桥接插件） | - | V1.0.8.4 | 必须导入 |
| AdapterBQT（百青藤适配器） | 2M | v9.450 | 仅支持AndroidX |
| AdapterCSJ（穿山甲适配器） | 12.5M | v7.5.1.0 | |
| AdapterGDT（优量汇适配器） | 2.4M | v4.662.1532 | |
| AdapterKS（快手适配器） | 6.3M | v4.4.20.1 | |
| AdapterMS（美数适配器） | 2.1M | v2.5.7.7 | |
| AdapterBZ（倍孜适配器） | 2M | v5.2.1.21 | |



## 2. 接入准备

### 2.1 复制文件

从Demo项目中复制以下文件到你的UniApp项目：

#### 2.1.1 原生插件（必须）

**步骤1：** 从 `xilu-uni-demo/nativeplugins/Xilu-AD/` 复制 `package.json` 到你的项目：

```
你的项目/
└── nativeplugins/
    └── Xilu-AD/
        └── package.json
```

**步骤2：** 从 `vendor/xilu/` 复制全部16个AAR到上述目录的 `android/` 子目录：

```
nativeplugins/Xilu-AD/android/
├── XiluADUniPlugin-release.aar      桥接插件（必须）
├── XiluSDKCore-v1.0.8.4.aar        核心SDK（必须）
├── AdapterBQT-v1.0.8.4.aar         百青藤适配器
├── AdapterBZ-v1.0.8.4.aar          百度资讯适配器
├── AdapterCSJ-v1.0.8.4.aar         穿山甲适配器
├── AdapterGDT-v1.0.8.4.aar         优量汇适配器
├── AdapterKS-v1.0.8.4.aar          快手适配器
├── AdapterMS-v1.0.8.4.aar          美数适配器
├── open_ad_sdk_7.5.1.0.aar         穿山甲SDK
├── GDT_SDK.4.662.1532.aar          优量汇SDK
├── KS_AD_4.4.20.1.aar              快手SDK
├── Baidu_MobAds_SDK-release_v9.450.aar  百度SDK
├── BZ_AD_5.2.1.21.aar              百度资讯SDK
├── MS_AD_2.5.7.7.aar               美数SDK
├── hms-ads-identifier-3.4.62.300.aar    华为OAID
└── mcs-ads-identifier-1.0.2.301.aar     荣耀OAID
```

#### 2.1.2 工具文件（必须）

将 `xilu-uni-demo/utils/` 下两个文件复制到你的项目：

```
你的项目/
└── utils/
    └── xilu-ad.js             SDK调用封装 + 常量配置（需要修改）
```



### 2.2 注册插件

#### 2.2.1 检查package.json

`nativeplugins/Xilu-AD/package.json` 已包含插件注册信息，**无需修改**：

```json
{
    "id": "Xilu-AD",
    "_dp_type": "nativeplugin",
    "_dp_nativeplugin": {
        "android": {
            "plugins": [
                { "type": "module", "name": "Xilu-AD", "class": "com.xilu.sdk.uni.XiluAdModule" },
                { "type": "component", "name": "xilu-banner", "class": "com.xilu.sdk.uni.XiluBannerComponent" },
                { "type": "component", "name": "xilu-native-express", "class": "com.xilu.sdk.uni.XiluNativeExpressComponent" },
                { "type": "component", "name": "xilu-native-feed", "class": "com.xilu.sdk.uni.XiluNativeFeedComponent" },
                { "type": "component", "name": "xilu-draw-vod", "class": "com.xilu.sdk.uni.XiluDrawVodComponent" }
            ]
        }
    }
}
```

#### 2.2.2 修改manifest.json

在你的 `manifest.json` 中添加插件引用：

```json
{
    "app-plus": {
        "nativePlugins": {
            "Xilu-AD": {
                "__plugin_version": "1.0.0"
            }
        },
        "distribute": {
            "android": {
                "abiFilters": ["armeabi-v7a", "arm64-v8a"]
            }
        }
    }
}
```



## 3. SDK初始化

### 3.1 初始化API

**init(config, callback)**

初始化SDK，必须在广告请求之前完成。

| 参数 | 类型 | 说明 |
|------|------|------|
| config | Object | 初始化配置对象，详见3.2 |
| callback | Function | 初始化结果回调 |

**isInit()**

判断SDK是否初始化完成。SDK初始化是异步的，请求广告前请确保初始化已完成。

| 返回值 | 类型 | 说明 |
|--------|------|------|
| result | Boolean | true表示已初始化完成 |



### 3.2 初始化参数说明

`utils/xilu-ad.js` 中的 `INIT` 对象：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| appId | String | 是 | - | 应用ID，在广告后台申请 |
| debug | Boolean | 否 | false | 调试模式，开发阶段设为true，**上线前务必置为false** |
| logDebug | Boolean | 否 | false | 是否打印 Xilu SDK 原生日志（tag = `ADXiluLog`）。默认关闭，排查"没广告/竞价失败"时设为 `true`，可看到参选平台数量、各平台成败等；排查完改回 `false` |
| agreePrivacyStrategy | Boolean | 否 | false | **【慎改】** 是否同意隐私政策，设为true将禁用一切设备信息读取，严重影响收益 |
| isCanUseLocation | Boolean | 否 | true | 是否可获取定位数据 |
| isCanUsePhoneState | Boolean | 否 | true | 是否可获取IMEI等设备信息 |
| isCanReadInstallList | Boolean | 否 | true | 是否可读取设备安装列表 |
| isCanUseReadWriteExternal | Boolean | 否 | false | 是否可读写外部存储 |
| isCanUseWifiState | Boolean | 否 | true | 是否可读取WIFI信息 |
| isCanUseOaid | Boolean | 否 | true | 是否可使用OAID |
| filterThirdQuestion | Boolean | 否 | true | 是否过滤第三方平台的问题广告 |
| isCanUseSensor | Boolean | 否 | true | 是否允许使用传感器 |
| customDeviceInfo | Object | 否 | {} | 隐私开关关闭时由宿主显式传入设备标识 |

**customDeviceInfo 参数说明：**

| 参数 | 类型 | 说明 |
|------|------|------|
| imei | String | 当isCanUsePhoneState=false时，可传入imei信息 |
| oaid | String | 开发者可以传入oaid，若不传或为空值则不使用oaid信息 |
| vaid | String | 开发者可以传入vaid，若不传或为空值则不使用vaid信息 |
| macAddress | String | 当isCanUseWifiState=false时，可传入Mac地址信息 |
| location | Object | 当isCanUseLocation=false时，可传入地理位置信息，包含latitude和longitude |

**关掉失败提示（正式包建议）：**

Demo 为了便于观察，广告加载失败时会弹 toast。正式接入时把 `utils/xilu-ad.js` 里的开关改成 `false` 即可全局关闭，页面代码不用动：

```js
export const AD_TOAST = {
    /** 广告加载/渲染失败是否弹 toast */
    ERROR: true      // 正式包改成 false
}
```

页面里失败统一走 `handleAdFailed(tag, e)`（demo 的页面已经是这个写法），关掉开关后不再弹提示，但失败原因仍会打到控制台，排查时不受影响。想自己控制提示 UI 的话，用它的返回值：

```js
import { handleAdFailed } from '@/utils/xilu-ad'

onAdFailed(e) {
    const err = handleAdFailed('feed', e)   // 返回值就是错误文本，不再自己调 showToast
    this.show = false
}
```



### 3.3 初始化示例

在你的首页（如 `pages/index/index.vue`）中调用初始化：

```html
<script>
import { init, isInit, INIT } from '@/utils/xilu-ad'

export default {
    data() {
        return { inited: false }
    },
    onShow() {
        if (!this.inited) {
            this.inited = true
            this.initSdk()
        }
    },
    methods: {
        initSdk() {
            // 方式一：通过isInit()判断是否初始化完成
            if (isInit()) return

            // 方式二：通过回调判断
            init(INIT, res => {
                console.log('SDK init:', res.event, res.data)
                if (res.event === 'onSuccess') {
                    // 初始化成功，可以请求广告
                } else {
                    // 初始化失败
                    console.error('SDK初始化失败:', res.data.error)
                }
            })
        }
    }
}
</script>
```

**注意：SDK初始化是异步的**，请在初始化完成后再请求广告。



## 4. 广告接入示例

### 4.1 开屏广告

<a id="ad_splash"></a>

开屏广告建议在闪屏页进行展示，通过拉起独立原生Activity实现。开屏广告的高度必须大于等于屏幕高度的75%，否则可能会影响收益计费。

#### 4.1.1 开屏广告API

**showSplash(config, callback)**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| config.posId | String | 是 | 广告位ID |
| config.splashType | Number | 否 | 样式：0沉浸全屏（去状态栏）、1全屏、2半屏，默认0 |
| config.logoHeightPx | Number | 否 | 半屏时底部logo占位高度（px），仅splashType=2生效 |
| config.loadType | Number | 否 | 加载方式：0收到即展示（默认）、1仅加载 |
| config.customSkipView | Boolean | 否 | 是否使用自定义跳过按钮，默认false |
| config.skipViewTimeMs | Number | 否 | 自定义跳过按钮倒计时（ms），默认5000 |
| config.adShakeDisable | Boolean | 否 | 是否禁用摇一摇，默认false |
| config.onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |

**回调事件：**

| 事件 | 说明 |
|------|------|
| onADTick | 广告倒计时剩余时长回调，`res.data.millisUntilFinished` 为剩余毫秒 |
| onAdReceive | 广告加载成功回调 |
| onAdExpose | 广告展示回调 |
| onAdClick | 广告点击回调 |
| onAdClose | 广告关闭回调 |
| onAdFailed | 广告失败回调，`res.data.error` 包含错误信息 |



#### 4.1.2 开屏广告示例

```html
<template>
    <view class="page">
        <button class="btn" @click="show">展示开屏广告</button>
    </view>
</template>

<script>
import { POS, SPLASH, showSplash } from '@/utils/xilu-ad'

export default {
    data() {
        return {
            splashType: SPLASH.DEFAULT_TYPE,
            logoHeightPx: SPLASH.LOGO_HEIGHT_PX
        }
    },
    methods: {
        show() {
            showSplash({
                posId: POS.splash,
                splashType: this.splashType,
                logoHeightPx: this.logoHeightPx,
                loadType: SPLASH.LOAD_AND_SHOW,
                customSkipView: SPLASH.CUSTOM_SKIP_VIEW,
                skipViewTimeMs: SPLASH.SKIP_VIEW_TIME_MS,
                onlySupportPlatform: SPLASH.ONLY_SUPPORT_PLATFORM,
                adShakeDisable: SPLASH.AD_SHAKE_DISABLE
            }, res => {
                const ev = res.event
                if (ev === 'onADTick') {
                    console.log('开屏倒计时:', res.data.millisUntilFinished)
                } else if (ev === 'onAdClose' || ev === 'onAdFailed') {
                    if (ev === 'onAdFailed') {
                        uni.showToast({ title: '广告获取失败 : ' + (res.data.error || ''), icon: 'none' })
                    }
                    setTimeout(() => uni.navigateBack(), 300)
                }
            })
        }
    }
}
</script>
```



### 4.2 横幅广告

<a id="ad_banner"></a>

横幅广告建议放置在**固定位置**，而非滚动列表中充当item。横幅广告的宽度将会撑满容器，高度自适应。**横幅广告必须在.nvue页面中使用，且必须显式指定宽高。**

#### 4.2.1 横幅广告组件

**`<xilu-banner>`** 原生组件

| 属性 | 类型 | 必填 | 说明 |
|------|------|------|------|
| posId | String | 是 | 广告位ID |
| sceneId | String | 否 | 场景ID |
| refreshInterval | Number | 否 | 自刷新间隔（秒），范围30~120，默认30 |
| adShakeDisable | Boolean | 否 | 是否禁用摇一摇，默认false |
| onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |

**组件事件：**

| 事件 | 说明 |
|------|------|
| @onAdReceive | 广告加载成功回调 |
| @onAdExpose | 广告展示回调 |
| @onAdClick | 广告点击回调 |
| @onAdClose | 广告关闭回调，开发者需要在此隐藏或移除广告容器 |
| @onAdFailed | 广告失败回调，`e.data.error` 包含错误信息 |



#### 4.2.2 横幅广告示例

```html
<template>
    <view class="page">
        <view style="width:750rpx; height:128rpx;">
            <xilu-banner
                v-if="show"
                style="width:750rpx; height:128rpx;"
                :posId="posId"
                :sceneId="sceneId"
                :refreshInterval="refreshInterval"
                :adShakeDisable="adShakeDisable"
                @onAdReceive="onEvent"
                @onAdClose="onEvent"
                @onAdFailed="onEvent" />
        </view>
        <button class="btn" @click="reload">{{ show ? '重新加载' : '加载广告' }}</button>
    </view>
</template>

<script>
import { POS, BANNER } from '@/utils/xilu-ad'

export default {
    data() {
        return {
            posId: POS.banner,
            sceneId: BANNER.SCENE_ID,
            refreshInterval: BANNER.AUTO_REFRESH_INTERVAL,
            adShakeDisable: BANNER.AD_SHAKE_DISABLE,
            show: false
        }
    },
    methods: {
        reload() {
            this.show = false
            this.$nextTick(() => {
                this.show = true
            })
        },
        onEvent(e) {
            const ev = e.type || ''
            if (ev === 'onAdFailed' || ev === 'onAdClose') {
                this.show = false
            }
        }
    }
}
</script>
```

> **注意：** 同一个ADXiluBannerAd只有一次loadAd有效，重新加载需用v-if切换重建组件实例。



### 4.3 激励视频广告

<a id="ad_reward_vod"></a>

将短视频融入到APP场景当中，用户观看短视频广告后可以给予一些应用内奖励。激励、全屏视频、插屏等广告对象**一次成功拉取的广告数据只允许展示一次**，还需展示请再次加载广告。

#### 4.3.1 激励视频API

**loadRewardVideo(config, callback)**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| config.posId | String | 是 | 广告位ID |
| config.muted | Boolean | 否 | 视频静音设置，默认false |
| config.sceneId | String | 否 | 广告场景ID |
| config.adShakeDisable | Boolean | 否 | 是否禁用摇一摇，默认false |
| config.onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |
| config.userId | String | 否 | 服务端奖励验证用户ID |
| config.rewardName | String | 否 | 奖励名称 |
| config.rewardAmount | Number | 否 | 奖励数量 |
| config.customData | String | 否 | 自定义数据 |

**showRewardVideo(config, callback)**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| config | Object | 是 | 空对象即可，展示逻辑由内部处理 |

**loadRewardVideo 回调事件：**

| 事件 | 说明 |
|------|------|
| onAdReceive | 广告加载成功回调 |
| onReward | 广告奖励回调，用户观看完成时触发 |
| onVideoCache | 广告视频缓存成功回调，部分渠道不会回调该方法 |
| onVideoComplete | 广告播放完毕回调 |
| onVideoError | 视频播放错误回调 |
| onAdExpose | 广告展示回调 |
| onAdClick | 广告点击回调 |
| onAdClose | 广告关闭回调 |
| onAdFailed | 广告获取失败回调 |

**showRewardVideo 回调事件：**

| 事件 | 说明 |
|------|------|
| onShowFailed | 展示失败，`res.data.error` 包含错误原因（无可用广告/广告未准备好/广告已失效） |



#### 4.3.2 激励视频示例

```html
<template>
    <view class="page">
        <button class="btn" @click="load">{{ loading ? '加载中...' : '获取激励视频广告' }}</button>
        <button class="btn" :disabled="!ready" @click="show">展示激励视频广告</button>
    </view>
</template>

<script>
import { POS, REWARD, loadRewardVideo, showRewardVideo } from '@/utils/xilu-ad'

export default {
    data() {
        return { ready: false, loading: false }
    },
    methods: {
        load() {
            this.ready = false
            this.loading = true
            loadRewardVideo({
                posId: POS.reward,
                muted: REWARD.PLAY_WITH_MUTE,
                sceneId: REWARD.SCENE_ID,
                adShakeDisable: REWARD.AD_SHAKE_DISABLE
            }, res => {
                const ev = res.event
                if (ev === 'onAdReceive') {
                    this.ready = true
                    this.loading = false
                    uni.showToast({ title: '激励视频广告获取成功' })
                } else if (ev === 'onReward') {
                    console.log('发放奖励')
                } else if (ev === 'onAdClose') {
                    this.ready = false
                } else if (ev === 'onAdFailed') {
                    this.loading = false
                    uni.showToast({ title: '广告获取失败 : ' + (res.data.error || ''), icon: 'none' })
                }
            })
        },
        show() {
            showRewardVideo({}, res => {
                if (res.event === 'onShowFailed') {
                    uni.showToast({ title: res.data.error, icon: 'none' })
                }
            })
        }
    }
}
</script>
```



### 4.4 插屏广告

<a id="ad_interstitial"></a>

插屏广告是移动广告的一种常见形式，在应用流程中弹出，当应用展示插屏广告时，用户可以选择点击广告，也可以将其关闭并返回应用。

#### 4.4.1 插屏广告API

**loadInterstitial(config, callback)**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| config.posId | String | 是 | 广告位ID |
| config.muted | Boolean | 否 | 视频静音设置，默认false |
| config.sceneId | String | 否 | 广告场景ID |
| config.onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |

**showInterstitial(config, callback)**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| config | Object | 是 | 空对象即可 |

**loadInterstitial 回调事件：**

| 事件 | 说明 |
|------|------|
| onAdReceive | 广告加载成功回调 |
| onAdReady | 广告准备完毕回调，部分渠道不会回调该方法 |
| onAdExpose | 广告展示回调 |
| onAdClick | 广告点击回调 |
| onAdClose | 广告关闭回调 |
| onAdFailed | 广告获取失败回调 |

**showInterstitial 回调事件：**

| 事件 | 说明 |
|------|------|
| onShowFailed | 展示失败，`res.data.error` 包含错误原因 |



#### 4.4.2 插屏广告示例

```html
<template>
    <view class="page">
        <button class="btn" @click="load">{{ loading ? '加载中...' : '获取插屏广告' }}</button>
        <button class="btn" :disabled="!ready" @click="show">展示插屏广告</button>
    </view>
</template>

<script>
import { POS, INTERSTITIAL, loadInterstitial, showInterstitial } from '@/utils/xilu-ad'

export default {
    data() {
        return { ready: false, loading: false }
    },
    methods: {
        load() {
            this.ready = false
            this.loading = true
            loadInterstitial({
                posId: POS.interstitial,
                muted: INTERSTITIAL.PLAY_WITH_MUTE,
                sceneId: INTERSTITIAL.SCENE_ID,
                onlySupportPlatform: INTERSTITIAL.ONLY_SUPPORT_PLATFORM
            }, res => {
                const ev = res.event
                if (ev === 'onAdReceive') {
                    this.ready = true
                    this.loading = false
                    uni.showToast({ title: '插屏广告获取成功' })
                } else if (ev === 'onAdClose') {
                    this.ready = false
                } else if (ev === 'onAdFailed') {
                    this.loading = false
                    uni.showToast({ title: '广告获取失败 : ' + (res.data.error || ''), icon: 'none' })
                }
            })
        },
        show() {
            showInterstitial({}, res => {
                if (res.event === 'onShowFailed') {
                    uni.showToast({ title: res.data.error, icon: 'none' })
                }
            })
        }
    }
}
</script>
```



### 4.5 全屏视频广告

<a id="ad_full_screen_vod"></a>

全屏视频广告是类似激励视频样式的广告形式，与激励视频不同之处在于全屏视频广告播放一定时间后即可跳过，同时全屏视频广告拥有跳过回调不具备奖励回调。

#### 4.5.1 全屏视频广告API

**loadFullScreenVod(config, callback)**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| config.posId | String | 是 | 广告位ID |
| config.onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |

**showFullScreenVod(config, callback)**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| config | Object | 是 | 空对象即可 |

**loadFullScreenVod 回调事件：**

| 事件 | 说明 |
|------|------|
| onAdReceive | 广告加载成功回调 |
| onVideoCache | 广告视频缓存成功回调，部分渠道不会回调该方法 |
| onVideoComplete | 广告播放完毕回调 |
| onVideoError | 视频播放错误回调 |
| onAdExpose | 广告展示回调 |
| onAdClick | 广告点击回调 |
| onAdClose | 广告关闭回调 |
| onAdFailed | 广告获取失败回调 |

**showFullScreenVod 回调事件：**

| 事件 | 说明 |
|------|------|
| onShowFailed | 展示失败，`res.data.error` 包含错误原因 |



#### 4.5.2 全屏视频广告示例

```html
<template>
    <view class="page">
        <button class="btn" @click="load">{{ loading ? '加载中...' : '获取全屏视频广告' }}</button>
        <button class="btn" :disabled="!ready" @click="show">展示全屏视频广告</button>
    </view>
</template>

<script>
import { POS, FULL_SCREEN, loadFullScreenVod, showFullScreenVod } from '@/utils/xilu-ad'

export default {
    data() {
        return { ready: false, loading: false }
    },
    methods: {
        load() {
            this.ready = false
            this.loading = true
            loadFullScreenVod({
                posId: POS.fullscreen,
                onlySupportPlatform: FULL_SCREEN.ONLY_SUPPORT_PLATFORM
            }, res => {
                const ev = res.event
                if (ev === 'onAdReceive') {
                    this.ready = true
                    this.loading = false
                    uni.showToast({ title: '全屏视频广告获取成功' })
                } else if (ev === 'onAdClose') {
                    this.ready = false
                } else if (ev === 'onAdFailed') {
                    this.loading = false
                    uni.showToast({ title: '广告获取失败 : ' + (res.data.error || ''), icon: 'none' })
                }
            })
        },
        show() {
            showFullScreenVod({}, res => {
                if (res.event === 'onShowFailed') {
                    uni.showToast({ title: res.data.error, icon: 'none' })
                }
            })
        }
    }
}
</script>
```



### 4.6 信息流模板广告

<a id="ad_native_express"></a>

信息流模板广告返回拼装好的广告视图，开发者只需将视图添加到相应容器即可。**信息流模板广告必须在.nvue页面中使用，且必须显式指定宽高。**

#### 4.6.1 信息流模板广告组件

**`<xilu-native-express>`** 原生组件

| 属性 | 类型 | 必填 | 说明 |
|------|------|------|------|
| posId | String | 是 | 广告位ID |
| sceneId | String | 否 | 广告场景ID |
| muted | Boolean | 否 | 视频静音设置，默认true |
| count | Number | 否 | 一次拉取条数，默认1，范围1~3 |
| adWidthPx | Number | 否 | 广告宽度（px），默认取容器宽度，容器宽度为0时取屏宽 |
| onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |

**组件事件：**

| 事件 | 说明 |
|------|------|
| @onAdReceive | 广告加载成功回调，`e.detail` 含 `count`、`platform`、`ecpm`、`ecpmPrecision`、`materialId`、`platformPosId` |
| @onAdRender | 广告渲染完成回调，`e.detail.height` 返回实际高度 |
| @onAdExpose | 广告展示回调 |
| @onAdClick | 广告点击回调 |
| @onAdClose | 广告关闭回调，开发者需要在此隐藏或移除广告容器 |
| @onAdFailed | 广告加载失败回调，`e.detail` 含 `error` / `errorText` / `errorCode` |
| @onRenderFailed | 广告渲染失败回调，`e.detail.error` 为失败原因 |



#### 4.6.2 信息流模板广告示例

```html
<template>
    <scroll-view class="page" scroll-y="true">
        <view class="ad-box" :style="{ height: adHeight + 'px' }">
            <xilu-native-express
                v-if="show"
                :posId="posId"
                :sceneId="sceneId"
                :muted="muted"
                :count="count"
                :onlySupportPlatform="onlySupportPlatform"
                :style="{ width: '750rpx', height: adHeight + 'px' }"
                @onAdReceive="onEvent"
                @onAdRender="onRender"
                @onAdExpose="onEvent"
                @onAdClick="onEvent"
                @onAdClose="onEvent"
                @onAdFailed="onEvent"
                @onRenderFailed="onEvent" />
        </view>
        <button class="btn" @click="reload">{{ show ? '重新加载' : '加载信息流广告' }}</button>
    </scroll-view>
</template>

<script>
import { POS, NATIVE, handleAdFailed } from '@/utils/xilu-ad'

export default {
    data() {
        return {
            posId: POS.nativeTemplate,
            sceneId: NATIVE.SCENE_ID,
            muted: NATIVE.PLAY_WITH_MUTE,
            count: NATIVE.COUNT_SINGLE,
            onlySupportPlatform: NATIVE.ONLY_SUPPORT_PLATFORM,
            show: false,
            adHeight: 400
        }
    },
    onLoad() { this.show = true },
    methods: {
        reload() {
            this.show = false
            this.$nextTick(() => { this.show = true })
        },
        onRender(e) {
            const h = (e.detail && e.detail.height) || 0
            if (h > 0) this.adHeight = h
        },
        onEvent(e) {
            const ev = e.type || ''
            if (ev === 'onAdFailed' || ev === 'onRenderFailed') {
                // 失败提示由 utils/xilu-ad.js 的 AD_TOAST.ERROR 统一控制
                handleAdFailed('native', e)
                this.show = false
            } else if (ev === 'onAdClose') {
                this.show = false
            }
        }
    }
}
</script>
```

> **注意：** nvue不支持wrap_content，需先设占位高度，再根据 `@onAdRender` 回调调整实际高度。



### 4.7 信息流自渲染广告

<a id="ad_native_feed"></a>

信息流自渲染广告与模板广告使用方式类似，但广告样式由开发者自行控制。**必须在.nvue页面中使用。**

#### 4.7.1 信息流自渲染广告组件

**`<xilu-native-feed>`** 原生组件

| 属性 | 类型 | 必填 | 说明 |
|------|------|------|------|
| posId | String | 是 | 广告位ID |
| sceneId | String | 否 | 广告场景ID |
| muted | Boolean | 否 | 视频静音设置，默认true |
| count | Number | 否 | 一次拉取条数，默认1，范围1~3 |
| adWidthPx | Number | 否 | 广告宽度（px），默认取容器宽度，容器宽度为0时取屏宽 |
| onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |

**组件事件：**

| 事件 | 说明 |
|------|------|
| @onAdReceive | 广告加载成功回调，`e.detail` 含 `count`、`platform`、`ecpm`、`ecpmPrecision`、`materialId`、`platformPosId` |
| @onAdRender | 广告渲染完成回调，`e.detail.height` 返回实际高度 |
| @onAdExpose | 广告展示回调 |
| @onAdClick | 广告点击回调 |
| @onAdClose | 广告关闭回调 |
| @onAdFailed | 广告加载失败回调，`e.detail` 含 `error`（SDK原始JSON）/ `errorText`（纯文本）/ `errorCode` |
| @onRenderFailed | 广告渲染失败回调，`e.detail.error` 为失败原因 |
| @onVideoLoad | 视频加载完成回调 |
| @onVideoStart | 视频开始播放回调 |
| @onVideoPause | 视频暂停回调 |
| @onVideoComplete | 视频播放完成回调 |
| @onVideoError | 视频播放错误回调 |

> 失败回调里的错误码 `-20115` 表示「该广告位本次没有平台出价」，属于正常的填充波动（原生 Demo 也会出现），按"暂无广告"处理即可，不要当成集成失败。



#### 4.7.2 信息流自渲染广告示例

```html
<template>
    <scroll-view class="page" scroll-y="true">
        <view class="ad-box" :style="{ height: adHeight + 'px' }">
            <xilu-native-feed
                v-if="show"
                :posId="posId"
                :sceneId="sceneId"
                :muted="muted"
                :count="count"
                :onlySupportPlatform="onlySupportPlatform"
                :style="{ width: '750rpx', height: adHeight + 'px' }"
                @onAdReceive="onEvent"
                @onAdRender="onRender"
                @onAdExpose="onEvent"
                @onAdClick="onEvent"
                @onAdClose="onEvent"
                @onAdFailed="onEvent"
                @onRenderFailed="onEvent" />
        </view>
        <button class="btn" @click="reload">{{ show ? '重新加载' : '加载自渲染广告' }}</button>
    </scroll-view>
</template>

<script>
import { POS, NATIVE, handleAdFailed } from '@/utils/xilu-ad'

export default {
    data() {
        return {
            posId: POS.nativeSelfRender,
            sceneId: NATIVE.SCENE_ID,
            muted: NATIVE.PLAY_WITH_MUTE,
            count: NATIVE.COUNT_SINGLE,
            onlySupportPlatform: NATIVE.ONLY_SUPPORT_PLATFORM,
            show: false,
            adHeight: 480
        }
    },
    onLoad() { this.show = true },
    methods: {
        reload() {
            this.show = false
            this.$nextTick(() => { this.show = true })
        },
        onRender(e) {
            const h = (e.detail && e.detail.height) || 0
            if (h > 0) this.adHeight = h
        },
        onEvent(e) {
            const ev = e.type || ''
            if (ev === 'onAdFailed' || ev === 'onRenderFailed') {
                // 失败提示由 utils/xilu-ad.js 的 AD_TOAST.ERROR 统一控制
                handleAdFailed('feed', e)
                this.show = false
            } else if (ev === 'onAdClose') {
                this.show = false
            }
        }
    }
}
</script>
```



### 4.8 Draw视频信息流广告

<a id="ad_draw_vod"></a>

Draw视频信息流广告用于沉浸式视频流场景，类似抖音/快手的竖屏视频广告。**必须在.nvue页面中使用，且容器宽高都必须大于0。**

#### 4.8.1 Draw视频信息流广告组件

**`<xilu-draw-vod>`** 原生组件

| 属性 | 类型 | 必填 | 说明 |
|------|------|------|------|
| posId | String | 是 | 广告位ID |
| count | Number | 否 | 一次拉取条数，默认1，范围1~3 |
| adWidthPx | Number | 否 | 广告宽度（px），默认取容器宽度，容器宽度为0时取屏宽 |
| adHeightPx | Number | 否 | 广告高度（px），默认取容器高度，容器高度为0时取屏高 |
| onlySupportPlatform | String | 否 | 仅请求某一渠道，仅debug模式生效 |

**组件事件：**

| 事件 | 说明 |
|------|------|
| @onAdReceive | 广告加载成功回调 |
| @onAdRender | 广告渲染完成回调，`e.detail.height` 返回实际高度 |
| @onAdExpose | 广告展示回调 |
| @onAdClick | 广告点击回调 |
| @onAdClose | 广告关闭回调 |
| @onAdFailed | 广告加载失败回调 |
| @onRenderFailed | 广告渲染失败回调 |
| @onVideoLoad | 视频加载完成回调 |
| @onVideoStart | 视频开始播放回调 |
| @onVideoPause | 视频暂停回调 |
| @onVideoComplete | 视频播放完成回调 |
| @onVideoError | 视频播放错误回调 |



#### 4.8.2 Draw视频信息流广告示例

```html
<template>
    <scroll-view class="page" scroll-y="true">
        <view class="ad-box" :style="{ height: adHeight + 'px' }">
            <xilu-draw-vod
                v-if="show"
                :posId="posId"
                :count="count"
                :onlySupportPlatform="onlySupportPlatform"
                :style="{ width: '750rpx', height: adHeight + 'px' }"
                @onAdReceive="onEvent"
                @onAdRender="onRender"
                @onAdExpose="onEvent"
                @onAdClick="onEvent"
                @onAdClose="onEvent"
                @onAdFailed="onEvent"
                @onRenderFailed="onEvent"
                @onVideoStart="onEvent"
                @onVideoPause="onEvent"
                @onVideoComplete="onEvent"
                @onVideoError="onEvent" />
        </view>
        <button class="btn" @click="reload">{{ show ? '重新加载' : '加载Draw视频信息流' }}</button>
    </scroll-view>
</template>

<script>
import { POS, DRAW } from '@/utils/xilu-ad'

export default {
    data() {
        return {
            posId: POS.draw,
            count: DRAW.COUNT,
            onlySupportPlatform: DRAW.ONLY_SUPPORT_PLATFORM,
            show: false,
            adHeight: 900
        }
    },
    onLoad() { this.show = true },
    methods: {
        reload() {
            this.show = false
            this.$nextTick(() => { this.show = true })
        },
        onRender(e) {
            const h = (e.detail && e.detail.height) || 0
            if (h > 0) this.adHeight = h
        },
        onEvent(e) {
            const ev = e.type || ''
            if (ev === 'onAdFailed' || ev === 'onRenderFailed') {
                this.show = false
            } else if (ev === 'onAdClose') {
                this.show = false
            }
        }
    }
}
</script>
```



## 5. 配置参数说明

### 5.1 广告位配置

`utils/xilu-ad.js` 中的 `POS` 对象：

| 参数 | 类型 | 说明 |
|------|------|------|
| splash | String | 开屏广告位ID |
| banner | String | Banner广告位ID |
| reward | String | 激励视频广告位ID |
| interstitial | String | 插屏广告位ID |
| fullscreen | String | 全屏视频广告位ID |
| nativeTemplate | String | 信息流模板广告位ID |
| nativeSelfRender | String | 信息流自渲染广告位ID |
| draw | String | Draw视频信息流广告位ID |

> 广告位ID在 https://uniad.dcloud.net.cn 后台申请获取。



### 5.2 各广告类型配置

#### SPLASH（开屏）

| 参数 | 类型 | 说明 |
|------|------|------|
| DEFAULT_TYPE | Number | 默认样式：0沉浸全屏、1全屏、2半屏 |
| LOAD_AND_SHOW | Number | 加载方式：收到即展示 |
| LOAD_ONLY | Number | 加载方式：仅加载 |
| CUSTOM_SKIP_VIEW | Boolean | 是否使用自定义跳过按钮 |
| SKIP_VIEW_TIME_MS | Number | 自定义跳过按钮倒计时（ms） |
| LOGO_HEIGHT_PX | Number | 半屏时底部logo高度（px），仅splashType=2生效 |
| AD_SHAKE_DISABLE | Boolean | 是否禁用摇一摇 |
| ONLY_SUPPORT_PLATFORM | String | 仅支持的平台 |

#### BANNER（横幅）

| 参数 | 类型 | 说明 |
|------|------|------|
| AUTO_REFRESH_INTERVAL | Number | 自刷新间隔（秒），范围30~120 |
| AD_SHAKE_DISABLE | Boolean | 是否禁用摇一摇 |
| ONLY_SUPPORT_PLATFORM | String | 仅支持的平台 |
| SCENE_ID | String | 场景ID |

#### NATIVE（信息流，模板+自渲染共用）

| 参数 | 类型 | 说明 |
|------|------|------|
| COUNT_SINGLE | Number | 单条页一次拉取条数 |
| COUNT_LIST | Number | 列表页每个广告槽一次拉取条数 |
| PLAY_WITH_MUTE | Boolean | 视频静音设置 |
| ONLY_SUPPORT_PLATFORM | String | 仅支持的平台 |
| SCENE_ID | String | 场景ID |

#### REWARD（激励视频）

| 参数 | 类型 | 说明 |
|------|------|------|
| PLAY_WITH_MUTE | Boolean | 视频静音设置 |
| AD_SHAKE_DISABLE | Boolean | 是否禁用摇一摇 |
| ONLY_SUPPORT_PLATFORM | String | 仅支持的平台 |
| SCENE_ID | String | 场景ID |

#### INTERSTITIAL（插屏）

| 参数 | 类型 | 说明 |
|------|------|------|
| PLAY_WITH_MUTE | Boolean | 视频静音设置 |
| ONLY_SUPPORT_PLATFORM | String | 仅支持的平台 |
| SCENE_ID | String | 场景ID |

#### FULL_SCREEN（全屏视频）

| 参数 | 类型 | 说明 |
|------|------|------|
| ONLY_SUPPORT_PLATFORM | String | 仅支持的平台 |

#### DRAW（Draw视频信息流）

| 参数 | 类型 | 说明 |
|------|------|------|
| COUNT | Number | 一次拉取条数 |
| ONLY_SUPPORT_PLATFORM | String | 仅支持的平台 |



## 6. 编译构建与真机测试

这一章说明**代码集成完成之后怎么把它跑起来**：选哪种编译方式、改了什么要重编什么、怎么装到手机上、怎么确认集成成功。

> 先记住一条最重要的规则：**原生插件属于"原生代码"，改插件必须重新出包（重新制作基座 / 重新打 APK）**。
> 只改页面 `.vue` 或 `utils/xilu-ad.js`，重新运行即可；只改 `nativeplugins/` 里的 AAR，**热刷新、重新运行都不会生效**。
> 很多同学"集成完了却完全没效果"，绝大多数就是在标准基座里跑，或者改了 AAR 没重编。



### 6.1 先确认走的是哪条路

原生插件只能用以下三种方式之一运行，**标准基座（HBuilderX 默认基座）一定不行**：

| 方式 | 用途 | 需要证书 | 说明 |
|------|------|----------|------|
| 自定义调试基座 | 开发联调，最常用 | 是（云打包时会校验） | 代码改完点"运行"即可，构建速度快 |
| HBuilderX 云打包 / 安心打包 | 出测试包、上架包 | 是 | 生成 apk 安装包 |
| 离线打包（`xilu-android-build/`） | 已有原生工程、需要 Gradle 定制 | 是 | 本地 Gradle 出 APK，可控性最强 |

> 判断依据：如果你的"运行到手机"里选的是**标准基座**，插件一定拿不到，页面会弹"未检测到 Xilu-AD 插件，请先制作自定义调试基座"。



### 6.2 方式一：自定义调试基座

**前置检查**（缺一不可）：

1. `nativeplugins/Xilu-AD/package.json` 存在；
2. `nativeplugins/Xilu-AD/android/` 下 16 个 AAR 齐全（见 2.1.1）；
3. 项目 `manifest.json` 的 `app-plus.nativePlugins` 里有 `Xilu-AD` 节点（见 2.2.2）；
4. HBuilderX 版本与 `nativeplugins` 下的 AAR 版本配套（本 Demo 用 HBuilderX 5.24 编译）。

**构建步骤**：

1. 菜单 `运行 → 运行到手机或模拟器 → 制作自定义调试基座`；
2. 选中**"自定义调试基座"**，填写包名（如 `com.yourcompany.yourapp`）；
3. 选择证书：自有证书，或点"如何生成证书"按提示生成；
4. 点击打包，等待云端返回成功；成功后基座会作为一个独立 App 出现在手机上；
5. 回到 HBuilderX，`运行 → 运行到 Android App 基座`，在弹窗里把**"运行基座"切换为你刚做的自定义基座**（这一步漏了就会跑回标准基座）；
6. 首次运行需要允许 USB 安装、开启"USB 调试"，手机上确认安装即可。

**什么时候要重新走一遍第 1~4 步**：

| 你改了什么 | 要不要重做基座 |
|------------|----------------|
| 页面 `.vue`、`.nvue`、`utils/*.js` | 不需要，重新"运行"即可 |
| `manifest.json` 的 `nativePlugins` / 权限 / 包名 | 需要 |
| `nativeplugins/` 下的任意 AAR | 需要 |
| 新增/删除了原生插件 | 需要 |



### 6.3 方式二：HBuilderX 云打包（出安装包给别人测）

1. 菜单 `发行 → 原生App-云打包`；
2. 勾选 Android、选择证书与包名，打包方式选"安心打包"或"传统打包"；
3. 勾选"打包时使用本地插件"，等待云端打包；
4. 完成后下载 `.apk`，传到手机安装（或直接用 HBuilderX 的"运行到手机"安装）。



### 6.4 方式三：离线打包（Android 工程自建 APK）

Demo 里的 `xilu-android-build/` 就是一个可直接构建的离线工程。完整流程是四步，**顺序不能乱**：

```
① 重新生成 uni-app 资源（HBuilderX）
        ↓  unpackage/resources/__UNI__F1B6163/www
② 把 www/ 复制到 Android 工程
        ↓  xilu-android-build/app/src/main/assets/apps/__UNI__F1B6163/www
③ 确认插件 AAR 是最新版
        ↓  vendor/xilu/XiluADUniPlugin-release.aar（16 个 AAR 之一）
④ Gradle 出 APK
```

**第①步：用 HBuilderX CLI 生成 App 资源**

HBuilderX 自带命令行工具 `cli.exe`，可以不开界面直接编译出离线打包用的 App 资源。

**1）先找到你机器上的 cli.exe**

安装目录随版本号变化，按下面的方法定位（**不要照抄下面示例里的版本号**）：

- 默认安装位置：`C:\Program Files\HBuilderX.<版本号>\HBuilderX\cli.exe`
- 自己确认一下（PowerShell 里执行，找到什么版本就写什么版本）：

```powershell
Get-ChildItem "C:\Program Files" -Filter "HBuilderX*" -Directory
# 输出示例：HBuilderX.5.24.2026081301
```

- 或者在 HBuilderX 界面里：`帮助 → 关于` 看版本；`工具 → 打开所在目录` 能直接定位到安装目录。
- 如果不是默认安装路径，把命令里的 `C:\Program Files\HBuilderX.5.24.2026081301\HBuilderX\cli.exe` 换成你自己的实际路径。

**2）确认项目已被 HBuilderX 导入**

> `--project` 只能操作 **HBuilderX 里已经导入的项目**。没导入过会报
> `项目 xxx 不存在，请先导入`。

```powershell
& "C:\Program Files\HBuilderX.5.24.2026081301\HBuilderX\cli.exe" project list
```

输出示例（序号 + 项目名 + 类型）：

```
1 - xilu-uni-demo(UniApp_VUE)
0:project list:OK
```

**3）执行编译**

`--project` 传**项目名**或**项目绝对路径**都可以，两种写法效果一样。
命令**写成一行**（Windows 下不要用 bash 的 `\` 续行）；下面两条在 PowerShell 和 CMD 里都能直接粘贴：

```
"C:\Program Files\HBuilderX.5.24.2026081301\HBuilderX\cli.exe" publish app-android --type appResource --project "D:\project\your-project"
```

```
"C:\Program Files\HBuilderX.5.24.2026081301\HBuilderX\cli.exe" publish app-android --type appResource --project "your-project"
```

> 注意引号别省：路径里有空格（`Program Files`），不加引号会被拆成多个参数。
> 嫌路径长可以先把 cli.exe 所在目录加到 PATH，之后直接写 `cli publish ...` 即可。

命令速查（把 `<你的HBuilderX路径>` 换成实际安装目录）：

```
"<你的HBuilderX路径>\cli.exe" project list                  :: 列出已导入项目
"<你的HBuilderX路径>\cli.exe" publish app-android --help    :: 查看该命令参数
```

**4）怎么算成功**

编译约 10~20 秒，成功时终端输出类似（整个过程 HBuilderX 开着或关着都可以）：

```
16:33:26.197 项目 your-project 编译成功。
16:33:26.311 项目 your-project 导出成功，路径为：D:\project\your-project\unpackage\resources
```

只要看到 **「编译成功」+「导出成功」** 就成了，产物在 `unpackage/resources` 目录。
如果报「项目 xxx 不存在，请先导入」，说明项目路径不对或还没导入过，回到第 2 步确认。

> 不想用命令行的，也可以在 HBuilderX 界面里点 `发行 → 原生App-本地打包 → 生成本地打包App资源`，效果一样。

**第②步：复制资源到 Android 工程**

把 `unpackage/resources/__UNI__F1B6163/www/` 整个目录**覆盖**到：

```
xilu-android-build/app/src/main/assets/apps/__UNI__F1B6163/www/
```

（`__UNI__F1B6163` 是你的 appid，在 `manifest.json` 里；换项目时对应目录名会变。）

**第③步：确认插件 AAR**

`xilu-android-build/app/build.gradle` 里通过 `fileTree` 引入 `vendor/xilu/*.aar`。
如果你改了插件的原生代码，必须重编桥接 AAR 并放到 `vendor/xilu/`（或直接替换该目录下的 `XiluADUniPlugin-release.aar`），否则打的还是旧代码。

**第④步：Gradle 出 APK**

```bash
cd xilu-android-build
./gradlew assembleDebug      # 测试包，输出在 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 正式包，需要配置签名
```

> 该工程要求 JDK 17 + Gradle 8.11.1 以上。若使用 HBuilderX 自带的 Gradle/JDK，直接在 HBuilderX 中用 Android Studio 打开本工程构建也可以。

**第⑤步（本工程已内置）：静默 DCloud uni-AD 的噪音提示**

打包时会自动执行一个补丁任务 `patchDcloudAdToast`，把 DCloud 基座里 uni-AD 模块弹出的
「应用的uni-ad业务状态异常（-9001/-9002），请登录 https://uniad.dcloud.net.cn」静默掉。
它是 uni-AD 自己的业务状态校验（与本插件无关，未在 uni-AD 后台绑定包名就会弹，且会刷屏），
提示文本由服务端下发、由框架 JS 调 `uni.showToast` 弹出，App 层拦不到，所以只能在原生收口处按内容过滤。

涉及三个文件，**移植到自建离线工程时要一起带走**：

```
tools/AdToastPatcher.java                                   # 打包期 ASM 补丁（ASM 取自 Gradle 缓存，离线可用）
app/src/main/java/com/qunze/xiju/adfix/AdToastFilter.java   # 只丢弃含 9001/9002 文本的提示（可在其中关日志）
app/build.gradle                                            # patchDcloudAdToast 任务 + preBuild 依赖
```

两个注意点：

- 补丁只对**本离线工程**生效。用 HBuilderX 自定义调试基座 / 云打包时没有这层补丁，仍会弹该提示 ——
  要么把上面三个文件移植过去，要么走官方正道：在 https://uniad.dcloud.net.cn 登录并绑定该 App 的包名，服务端就不再下发这条状态异常。
- 升级 DCloud 离线 SDK（替换 `lib.5plus.base-release.aar`）后，补丁任务会**校验插入点，找不到就直接构建失败**，
  按提示核对 `tools/AdToastPatcher.java` 里的方法签名即可；不需要时删掉该任务与 `patched-libs/` 即回退成原版 AAR。



### 6.5 怎么确认集成成功

装好之后**按顺序验证这三件事**，任何一步不对都能定位到具体原因。

**第 1 步：插件是否被识别**

进入任意广告页面，如果弹出 Toast **"未检测到 Xilu-AD 插件，请先制作自定义调试基座"**，说明插件没进包 —— 回到 6.1 检查基座选择（90% 是跑了标准基座）。

**第 2 步：看日志确认 SDK 初始化**

```bash
adb logcat | findstr /I "Xilu"
```

- 有 SDK 相关日志输出 → 插件已加载、`init` 已执行；
- 完全没有 `Xilu` 字样 → 插件没被加载，回到第 1 步。

**第 3 步：跑一次广告，确认回调**

以开屏广告为例（Demo：`pages/splash/splash.vue`），点"展示开屏广告"后正常的日志顺序是：

```
XiluSplash: onCreate splashType=0 (0=沉浸全屏 1=全屏 2=半屏) sdk=36
XiluSplash: afterFirstLayout decorH=2400 rootH=2400 screenH=2255 insetTB=0/0
```

- `splashType=0` 且 `insetTB=0/0` → 沉浸全屏生效，状态栏/导航栏已隐藏、广告铺满整屏；
- `splashType=1` 或 `2` → 这两个样式**按设计不隐藏系统栏**，上下会露出底色，这是正常的，不要当 bug 追；
- `afterFirstLayout` 每次开屏只应出现 **1 行**；若刷屏，说明有布局回调死循环，需检查是否有代码在布局回调里重复设置窗口属性。



### 6.6 常见"没效果"问题速查

| 现象 | 原因 | 解决 |
|------|------|------|
| Toast：未检测到 Xilu-AD 插件 | 跑在标准基座 | 制作并使用自定义调试基座（6.2） |
| 插件以前正常，改了 AAR 后行为不变 | 没有重做基座/重打 APK | 重做基座（6.2）或重打 APK（6.3/6.4） |
| 完全没有广告，也没有失败回调 | `init` 未执行或初始化失败 | 在 `init` 回调里打印结果，确认 `onSuccess` 后再请求广告 |
| 有失败回调但不出广告 | 广告位ID不对 / 该广告位无填充 / 未在后台配置 | 核对 `utils/xilu-ad.js` 中的 `POS` 与后台广告位ID一致 |
| 只想验证某一个渠道 | 需要指定平台 | `init` 的 `debug` 置 `true`，广告请求传 `onlySupportPlatform`（仅 debug 生效） |
| 开屏广告上下有白边 | `splashType` 不是 0 | 用 `SPLASH.DEFAULT_TYPE = 0`（沉浸全屏，去状态栏） |
| 离线包一切正常但日志里没有插件类 | 资源没同步 | 重新执行 6.4 的第①~②步，确认 `assets/apps/<appid>/www` 已更新 |
| HBuilderX 打包报资源校验失败（库工程） | 库工程里有 compileOnly 依赖 | 使用 App 工程打包；库工程单独构建时跳过资源校验任务 |
| 启动刷屏弹「应用的uni-ad业务状态异常（-9001/-9002），请登录…」 | DCloud uni-AD 模块的业务状态校验（与 Xilu 插件无关，未绑定包名就会弹） | 离线工程 `xilu-android-build` 已内置静默补丁（6.4 第⑤步）；其它打包方式见该步说明 |

> 提示：`init` 的 `debug: true` 建议开发期一直打开，失败原因会打印得更详细；**上线前务必置为 `false`**（见 3.2 初始化参数说明）。



### 6.7 完整流程速查

**日常开发（改页面/JS）**

```
改代码 → HBuilderX 运行到自定义基座 → 看日志验证
```

**改了原生插件（AAR）**

```
改插件源码 → 重新编出 AAR → 替换 nativeplugins/Xilu-AD/android/ 下的 AAR
          → 重新制作自定义基座 → 运行 → 看日志验证
```

**离线出 APK**

```
HBuilderX CLI 编译出 App 资源 → 覆盖到 assets/apps/<appid>/www
→ 确认 vendor/xilu/ 下 AAR 是最新 → gradlew assembleDebug
→ adb install -r app-debug.apk → adb logcat 验证
```





