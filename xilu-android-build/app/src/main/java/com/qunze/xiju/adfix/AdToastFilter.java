package com.qunze.xiju.adfix;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * DCloud uni-AD 噪音提示过滤器（配合打包期字节码补丁 {@code tools/AdToastPatcher.java}）。
 *
 * <p>背景：项目不使用 uni-AD，但框架视图层 bundle（{@code view.umd.min.js}）里带 uni-AD 的 JS 模块，
 * 启动后会弹出服务端下发的「应用的uni-AD业务状态异常（-9001/-9002），请登录 … 处理」。
 * 该提示由服务端下发、且可能走多条原生通道，App 层 patch {@code uni.showToast} 拦不到（实测无效）。
 * 所以在原生各收口处按内容拦截：命中即提前 return，其它提示不受影响。
 *
 * <p>补丁插入的收口（见 AdToastPatcher）：
 * <ul>
 *   <li>{@code NativeUIFeatureImpl.execute(...)} —— NativeUI 总入口；</li>
 *   <li>{@code NativeUIFeatureImpl.a(IApp, IWebview, String, JSONObject)} —— toast 落地方法；</li>
 *   <li>{@code io.dcloud.p.g0.show()} —— DCloud 自定义 Toast 的显示方法（构造参数 / TextView / 整个视图树）；</li>
 *   <li>{@code io.dcloud.p.c$a.run()} —— 直接用 {@code Toast.makeText} 弹的那条。</li>
 * </ul>
 */
public final class AdToastFilter {

    private static final String TAG = "AdToastFilter";

    /** 需要静默的错误码（服务端下发文本里带这些码就丢弃） */
    private static final String[] SILENT_CODES = {"9001", "9002"};

    /**
     * 文案特征：这条提示的正文固定是「应用的uni-AD业务状态异常（-90xx）…」，9001/9002 只差一个码。
     * 服务端换码时按文案拦也不会漏，比只认码更稳。
     */
    private static final String[] SILENT_TEXTS = {"uni-AD业务状态异常", "uni-ad业务状态异常"};

    /** 命中时打一条日志（同一段文本只打一次），便于真机核对；不需要时置 false */
    private static final boolean LOG_SILENCED = true;

    /** 诊断开关：把每次检查到的内容都打印出来（排查"漏网"时临时打开） */
    private static final boolean LOG_ALL = true;

    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<String>());

    /** NativeUI 总入口：JS 传来的参数数组（JSON 字符串都在里面） */
    public static boolean shouldSilence(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (containsSilentCode(arg, "execute-args")) {
                return true;
            }
        }
        return false;
    }

    /** toast 落地方法：文案（富文本 markup）和样式参数分开传 */
    public static boolean shouldSilence(String text) {
        return containsSilentCode(text, "text");
    }

    /** toast 落地方法：样式参数 JSON（正常只有 type/duration/style，兜底也判一次） */
    public static boolean shouldSilence(JSONObject options) {
        return options != null && containsSilentCode(options.toString(), "json");
    }

    /**
     * weex 引擎（uniapp-v8，跑在 :jse 进程）的 {@code WXModalUIModule.toast/alert/confirm/prompt}
     * 用的是 fastjson，参数里带 message/内容 —— 9001 那条就是从这条通道弹出来的（实测）。
     */
    public static boolean shouldSilence(com.alibaba.fastjson.JSONObject options) {
        return options != null && containsSilentCode(options.toString(), "wx-modal");
    }

    /** DCloud 自定义 Toast：文案可能放在 TextView 里（不一定是构造参数） */
    public static boolean shouldSilence(TextView textView) {
        if (textView == null) {
            return false;
        }
        CharSequence cs = textView.getText();
        return cs != null && containsSilentCode(cs.toString(), "textView");
    }

    /** DCloud 自定义 Toast：文案可能挂在 Toast 的整个视图树里（含调用方传入的 View） */
    public static boolean shouldSilence(View view) {
        if (view == null) {
            return false;
        }
        if (view instanceof TextView && shouldSilence((TextView) view)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (shouldSilence(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsSilentCode(String text, String channel) {
        if (text == null || text.length() == 0) {
            return false;
        }
        String hit = null;
        for (String code : SILENT_CODES) {
            if (text.contains(code)) {
                hit = code;
                break;
            }
        }
        if (hit == null) {
            for (String frag : SILENT_TEXTS) {
                if (text.contains(frag)) {
                    hit = frag;
                    break;
                }
            }
        }
        if (hit == null) {
            if (LOG_ALL && LOGGED.add("pass|" + channel + "|" + text)) {
                Log.i(TAG, "pass (" + channel + "): " + text);
            }
            return false;
        }
        if (LOG_SILENCED && LOGGED.add(hit + "|" + text)) {
            Log.i(TAG, "silenced (" + hit + ", " + channel + "): " + text);
        }
        return true;
    }

    private AdToastFilter() {
    }
}
