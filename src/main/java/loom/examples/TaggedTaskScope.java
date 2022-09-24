package loom.examples;

import jdk.incubator.concurrent.StructuredTaskScope;
import one.profiler.AsyncProfiler;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class TaggedTaskScope<T> extends StructuredTaskScope<T> {

    public TaggedTaskScope() {
        super();
    }

    private static final long LONG_PHI = 0x9E3779B97F4A7C15L;
    private static long mix(final long x) {
        long h = x * LONG_PHI;
        h ^= h >>> 32;
        return h ^ (h >>> 16);
    }

    private static ConcurrentHashMap<String, Long> fids = new ConcurrentHashMap<>();
    private static ThreadLocal<CallableWithStack> localStack = new ThreadLocal<>();
    private static AsyncProfiler apInstance = AsyncProfiler.getInstance("/home/pnf/dev/async-profiler/build/libasyncProfiler.so");
    private static long rwsRunId = AsyncProfiler.getMethodID(CallableWithStack.class, "call", "()Ljava/lang/Object;", false);
    private static AtomicLong instId = new AtomicLong(0);

    private static class CallableWithStack<A> implements Callable<A> {
        private Callable<A> inner;
        private int n;
        private long hash;
        private String fname;
        private long fid;
        CallableWithStack<Object> tail;

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(fname);
            var e = (CallableWithStack<Object>) this;
            while((e = e.tail) != null) {
                sb.append("->");
                sb.append(e.fname);
            }
            return sb.toString();
        }

        public CallableWithStack(Callable<A> inner) {
            this.inner = inner;
            // Create a string representation of the current position in the code.  This can be done much more efficiently
            // using a compiler plugin or java agent.
            var walker  = StackWalker.getInstance();
            fname = walker.walk(s ->
                    s.dropWhile(f -> !f.getMethodName().equals("fork")).skip(1).findFirst()
            ).map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber()).orElseGet(() -> "Unknown");
            tail = localStack.get();
            // Accumulate a unique-ish ID for the full stacks. In a more serious implementation, we'd dilute collisions by
            // mixing in a periodically changing value.
            hash = mix(fname.hashCode());
            if(tail != null) {
                hash = hash ^ tail.hash;
                n = tail.n + 1;
            } else
                n = 1;
            // Get a char* pointer.
            fid = fids.computeIfAbsent(fname, f -> AsyncProfiler.saveString(f));
        }

        private void saveFrames(long signal) {
            // If a sample occurs, this will be a match, so we know we should save the stack.  In "real" applications,
            // we'd use some kind of leaky map to avoid saving the same stack too often.
            if(signal == AsyncProfiler.getAwaitSampledSignal()) {
                var s = new long[(n + 1)];
                s[0] = hash;
                var i = 1;
                var e = (CallableWithStack<Object>) this;
                while(e != null) {
                    s[i++] = e.fid;
                    e = e.tail;
                }
                assert(i == n+1);
                apInstance.saveAwaitFrames(2, s, n);
            }
        }

        @Override
        public A call() throws Exception {
            var prevStack = localStack.get();
            // Make the await stack available to any recursive calls that might fork
            localStack.set(this);
            // Create a unique signal for async-profile to indicate that a sample has occurred
            var signal = instId.incrementAndGet();
            // Should a sample occur, async-profiler will search for the specified methodID, replace it with the accumulated
            // hash of the await stack, and set the signal so we can check it below.
            AsyncProfiler.setAwaitStackId(hash, signal, rwsRunId);
            var ret = inner.call();
            saveFrames(signal);
            // Restore the await stack for other calls.
            localStack.set(prevStack);
            return ret;
        }
    }

    @Override
    public <U extends T> Future<U> fork(Callable<? extends U> task) {
        return super.fork(new CallableWithStack<>(task));
    }

}
