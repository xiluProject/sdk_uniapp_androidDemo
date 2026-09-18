<template>
    <view class="menu">
        <!-- 排列：整行 2 个 + 信息流 2×2 -->
        <button class="menu-btn" :style="btnStyle" @click="go('/pages/splash/splash')">开屏广告</button>
        <button class="menu-btn" :style="btnStyle" @click="go('/pages/banner/banner')">横幅广告</button>

        <view class="menu-row">
            <button class="menu-btn half" :style="btnStyle" @click="go('/pages/native/native')">信息流模板</button>
            <button class="menu-btn half" :style="btnStyle" @click="go('/pages/feed/feed')">信息流自渲染</button>
        </view>
        <view class="menu-row">
            <button class="menu-btn half" :style="btnStyle" @click="go('/pages/native-list/native-list')">信息流模板列表</button>
            <button class="menu-btn half" :style="btnStyle" @click="go('/pages/feed-list/feed-list')">信息流自渲染列表</button>
        </view>

        <button class="menu-btn" :style="btnStyle" @click="go('/pages/interstitial/interstitial')">插屏广告</button>
        <button class="menu-btn" :style="btnStyle" @click="go('/pages/reward/reward')">激励视频广告</button>
    </view>
</template>

<script>
import { init, isInit, INIT, AD_TOAST, adErrorText } from '@/utils/xilu-ad'

/** 菜单里最长的按钮文案字数（"信息流模板列表"），换行与否由它决定 */
const LONGEST_LABEL_CHARS = 7

export default {
    data() {
        return {
            inited: false,
            initDone: false
        }
    },
    computed: {
        /** 按屏宽动态算按钮字号（半行可用宽度 ÷ 最长文案字数，夹在 11~18px），保证文字不换行 */
        menuFontSize() {
            let w = 375
            try {
                w = uni.getSystemInfoSync().windowWidth || w
            } catch (e) { /* 取不到就按 375 估 */ }
            const halfBtn = (w - 24 - 12 - 32) / 2
            const fit = halfBtn / LONGEST_LABEL_CHARS * 0.92
            return Math.max(11, Math.min(18, Math.floor(fit)))
        },
        btnStyle() {
            return { fontSize: this.menuFontSize + 'px' }
        }
    },
    onShow() {
        if (!this.inited) {
            this.inited = true
            this.initSdk()
        }
    },
    methods: {
        go(url) {
            uni.navigateTo({ url })
        },
        initSdk() {
            if (isInit()) {
                this.initDone = true
                return
            }
            init(INIT, res => {
                if (res.event === 'onSuccess') {
                    this.initDone = true
                    if (AD_TOAST.ERROR) {
                        uni.showToast({ title: 'SDK初始化成功', icon: 'none' })
                    }
                    return
                }
                // 初始化失败原因写入控制台；是否弹提示由 AD_TOAST.ERROR 统一控制
                const err = adErrorText((res.data && res.data.error) || '')
                console.log('[xilu-ad] init failed:', err)
                if (AD_TOAST.ERROR) {
                    uni.showToast({ title: 'SDK初始化失败', icon: 'none' })
                }
            })
        }
    }
}
</script>

<style>
.menu {
    padding: 24rpx;
}

.menu-btn {
    margin-bottom: 24rpx;
    /* 兜底：字号没算准时也不换行，宁可省略号 */
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.menu-row {
    display: flex;
    flex-direction: row;
}

.half {
    flex: 1;
}

.half:first-child {
    margin-right: 12rpx;
}

.half:last-child {
    margin-left: 12rpx;
}
</style>
