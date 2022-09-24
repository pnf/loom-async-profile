package loom.examples;

import jdk.incubator.concurrent.StructuredTaskScope;
import one.profiler.AsyncProfiler;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class StrConcEx {

    static double burnCpu(long ms, double x0)  {
        var t = System.nanoTime() + ms * 1000 * 1000;
        var x = x0;
        while(System.nanoTime() < t) {
            var i = 10000;
            while (i > 0) {
                x = x * 1.1;
                x = Math.sin(x);
                i -= 1;
                x *= (1.0/1.1);
            }
        }
        return x;
    }

    private static StructuredTaskScope<Double> newScope() {
        return new TaggedTaskScope<>();
    }

    double expensive1() {
      return burnCpu(1000, 1.1);
    }

    double expensive2()  {
        return burnCpu(500, 1.2);
    }

    double expensive3() {
        return burnCpu(750, 1.3);
    }

    double foo() throws InterruptedException {
        var s = newScope();
        var f1 = s.fork(this::expensive1);
        var f2 = s.fork(this::expensive2);
        s.join();
        return f1.resultNow() + f2.resultNow();
    }

    double bar() throws InterruptedException {
        var s = newScope();
        var f2 = s.fork(this::foo);
        var f3 = s.fork(this::expensive3);
        s.join();
        return f2.resultNow() + f3.resultNow();
    }

    double boo() throws InterruptedException {
        var s = newScope();
        var ff = s.fork(this::foo);
        var fb = s.fork(this::bar);
        s.join();
        return ff.resultNow() + fb.resultNow();
    }

    double myCalc() throws InterruptedException{
        var s = newScope();
        var ff = s.fork(this::foo);
        var fb = s.fork(this::boo);
        s.join();
        return ff.resultNow() + fb.resultNow();
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        var ap = AsyncProfiler.getInstance();
        var tp = "html";
        System.out.println(ap.execute("start,event=cpu,cstack=fp,interval=5ms,file=frames." + tp + ",simple"));
        var sce = new StrConcEx();
        var scope = newScope();
        var futures = IntStream.rangeClosed(1, 5).mapToObj( i -> scope.fork(sce::myCalc)).collect(Collectors.toList());
        scope.join();
        var result = futures.stream().mapToDouble(Future::resultNow).sum();
        System.out.println(ap.execute("stop,file=frames." + tp));
        System.out.println(result);
    }
}