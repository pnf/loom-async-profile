package loom.examples;

public class VirtualThreadExample {
    public static void main(String[] args) throws InterruptedException {
        Runnable printThread = () -> System.out.println(Thread.currentThread());

        Thread virtualThread = Thread.startVirtualThread(printThread);
        Thread kernelThread = Thread.ofPlatform().start(printThread);

        virtualThread.join();
        kernelThread.join();
    }
}
