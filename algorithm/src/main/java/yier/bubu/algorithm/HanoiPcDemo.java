package yier.bubu.algorithm;

import java.util.ArrayDeque;
import java.util.Deque;

public class HanoiPcDemo {

    private static class Frame {
        int pc;

        int n;
        char from;
        char to;
        char via;

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
            int nextPc = f.pc + 1;

            switch (f.pc) {
                case 0:
                    if (f.n == 1) {
                        System.out.println(f.from + " -> " + f.to);
                        stack.pop();
                        retval = 1;
                    }
                    break;

                case 1:
                    stack.push(new Frame(f.n - 1, f.from, f.via, f.to));
                    break;

                case 2:
                    f.c1 = retval;
                    break;

                case 3:
                    stack.push(new Frame(1, f.from, f.to, f.via));
                    break;

                case 4:
                    stack.push(new Frame(f.n - 1, f.via, f.to, f.from));
                    break;

                case 5:
                    f.c2 = retval;
                    break;

                case 6:
                    stack.pop();
                    retval = f.c1 + f.c2 + 1;
                    break;

                default:
                    throw new IllegalStateException("Unknown pc: " + f.pc);
            }

            f.pc = nextPc;
        }

        return retval;
    }
}
