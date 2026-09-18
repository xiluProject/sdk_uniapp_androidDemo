package com.qunze.xiju.adfix;

import android.util.Log;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** DCloud uni-AD 噪音提示过滤器（配合打包期补丁 tools/AdToastPatcher.java） */
public final class AdToastFilter {

    private static final String TAG = "AdToastFilter";

    /** 需要静默的错误码（服务端下发文本里带这些码就丢弃） */
    private static final String[] SILENT_CODES = {"9001", "9002"};

    /**
     * 文案特征：这条提示的正文固定是「应用的uni-AD业务状态异常（-90xx）…」。
     * 服务端换码时按文案拦也不会漏，比只认码更稳；落地域名也当特征，换码/换措辞都能盖住。
     */
    private static final String[] SILENT_TEXTS = {
            "uni-AD业务状态异常", "uni-ad业务状态异常", "uniad.dcloud.net.cn"
    };

    /** 命中时打一条日志（同一段文本只打一次） */
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

    /** NativeUI 入口被调用时记录调用栈（同一栈只记一次） */
    public static void noteToastEvent(String where) {
        StringBuilder sb = new StringBuilder("toast-event ").append(where);
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(st.length, 10); i++) {
            sb.append("\n    at ").append(st[i]);
        }
        if (LOGGED.add("evt|" + sb)) {
            Log.i(TAG, sb.toString());
        }
    }

    private static boolean containsSilentCode(String text, String channel) {
        if (text == null || text.length() == 0) {
            return false;
        }
        // 大小写无关：服务端/本地文案里 "uni-AD" 与 "uni-ad" 两种都出现过
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String hit = null;
        for (String code : SILENT_CODES) {
            if (lower.contains(code)) {
                hit = code;
                break;
            }
        }
        if (hit == null) {
            for (String frag : SILENT_TEXTS) {
                if (lower.contains(frag.toLowerCase(java.util.Locale.ROOT))) {
                    hit = frag;
                    break;
                }
            }
        }
        // 结构无关兜底：服务端文案的措辞/空格/数字全半角并不一致，只认码会漏，
        // 只要出现这几个特征就丢弃。本工程不使用 uni-AD，不会误伤正常提示。
        if (hit == null) {
            if (lower.contains("uni-ad") || lower.contains("uniad") || lower.contains("业务状态异常")) {
                hit = "uni-ad(兜底)";
            }
        }
        if (hit == null) {
            if (LOG_ALL && LOGGED.add("pass|" + channel + "|" + text)) {
                Log.i(TAG, "pass (" + channel + "): " + text + caller());
            }
            return false;
        }
        if (LOG_SILENCED && LOGGED.add(hit + "|" + text)) {
            Log.i(TAG, "silenced (" + hit + ", " + channel + "): " + text + caller());
        }
        return true;
    }

    /** 调用方栈（近几层），用于定位"谁在弹"；LOG_ALL=false 时不打，避免刷日志 */
    private static String caller() {
        if (!LOG_ALL) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 2; i < Math.min(st.length, 8); i++) {
            sb.append("\n    at ").append(st[i]);
        }
        return sb.toString();
    }

    private AdToastFilter() {
    }
}
