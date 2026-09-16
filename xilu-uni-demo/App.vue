<script>
// 应用壳：uni-app 的 App.vue 不渲染界面（无 template）
// 无隐私弹窗：SDK 初始化(init)在首页 pages/index/index.vue 的 onShow 里直接执行
export default {
    onLaunch: function () { },
    onShow: function () { },
    onHide: function () { }
}
</script>

<style>
/* uni-app 编译管线会请求 App.vue 的 style 块，不能删除（与官方默认模板同构）；页面公共样式可写在这里 */
</style>
