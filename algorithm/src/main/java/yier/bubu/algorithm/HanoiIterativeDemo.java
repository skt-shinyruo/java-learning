package yier.bubu.algorithm;

import java.util.ArrayDeque;
import java.util.Deque;

public class HanoiIterativeDemo {

    private static class Frame {
        int n;
        char from;
        char to;
        char via;
        int state; // 0: 先处理左递归  1: 输出移动  2: 处理右递归
        int c1;
        int c2;

        Frame(int n, char from, char to, char via) {
            this.n = n;
            this.from = from;
            this.to = to;
            this.via = via;
        }
    }

    public static void main(String[] args) {
        hanoi(3, 'A', 'C', 'B');
    }

    public static int hanoi(int n, char from, char to, char via) {
        if (n <= 0) {
            return 0;
        }

        Deque<Frame> stack = new ArrayDeque<Frame>();
        int retval = 0;
        stack.push(new Frame(n, from, to, via));

        while (!stack.isEmpty()) {
            Frame f = stack.peek();

            if (f.n == 1) {
                System.out.println(f.from + " -> " + f.to);
                stack.pop();
                retval = 1;
                continue;
            }

            if (f.state == 0) {
                f.state = 1;
                stack.push(new Frame(f.n - 1, f.from, f.via, f.to));
            } else if (f.state == 1) {
                f.c1 = retval;
                System.out.println(f.from + " -> " + f.to);
                retval = 1;
                f.state = 2;
                stack.push(new Frame(f.n - 1, f.via, f.to, f.from));
            } else {
                stack.pop();
                f.c2 = retval;
                retval = f.c1 + f.c2 + 1;
            }
        }

        return retval;
    }
}
