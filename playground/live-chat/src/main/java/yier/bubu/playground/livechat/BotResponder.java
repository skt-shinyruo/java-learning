package yier.bubu.playground.livechat;

public final class BotResponder {

    public String welcome() {
        return "您好，这里是机器人客服。请描述您的问题，我会尽快为您解答。";
    }

    public String reply(String customerMessage) {
        return "收到：「" + customerMessage + "」";
    }

    public String timeoutNotice() {
        return "会话已超时关闭";
    }
}
