package yier.bubu.algorithm;

import java.util.ArrayDeque;
import java.util.Deque;

public class HanoiIterativeDemo {

    private static class Frame {
        int n;
        char from;
        char aux;
        char to;
        int state; // 0: 先处理左递归  1: 输出移动  2: 处理右递归

        Frame(int n, char from, char aux, char to) {
            this.n = n;
            this.from = from;
            this.aux = aux;
            this.to = to;
        }
    }

    public static void main(String[] args) {
        hanoi(3, 'A', 'B', 'C');
    }

    public static void hanoi(int n, char from, char aux, char to) {
        if (n <= 0) {
            return;
        }

        Deque<Frame> stack = new ArrayDeque<Frame>();
        stack.push(new Frame(n, from, aux, to));

        while (!stack.isEmpty()) {
            Frame f = stack.peek();

            if (f.n == 1) {
                System.out.println("把第1个盘子从 " + f.from + " 移到 " + f.to);
                stack.pop();
                continue;
            }

            if (f.state == 0) {
                f.state = 1;
                stack.push(new Frame(f.n - 1, f.from, f.to, f.aux));
            } else if (f.state == 1) {
                System.out.println("把第" + f.n + "个盘子从 " + f.from + " 移到 " + f.to);
                f.state = 2;
                stack.push(new Frame(f.n - 1, f.aux, f.from, f.to));
            } else {
                stack.pop();
            }
        }
    }
}
