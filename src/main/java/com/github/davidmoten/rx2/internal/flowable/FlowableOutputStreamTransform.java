package com.github.davidmoten.rx2.internal.flowable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.reactivex.Flowable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.Function;
import io.reactivex.internal.fuseable.SimplePlainQueue;
import io.reactivex.internal.queue.SpscLinkedArrayQueue;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.internal.util.BackpressureHelper;
import io.reactivex.internal.util.EmptyComponent;
import io.reactivex.plugins.RxJavaPlugins;

public final class FlowableOutputStreamTransform extends Flowable<byte[]> {

    private final Flowable<byte[]> source;
    private final Function<OutputStream, OutputStream> transform;
    private final int bufferSize;
    private final int batchSize;

    public FlowableOutputStreamTransform(Flowable<byte[]> source,
            Function<OutputStream, OutputStream> transform, int bufferSize, int batchSize) {
        this.source = source;
        this.transform = transform;
        this.bufferSize = bufferSize;
        this.batchSize = batchSize;
    }

    @Override
    protected void subscribeActual(Subscriber<? super byte[]> child) {
        PipeOutSubscriber subscriber;
        try {
            subscriber = new PipeOutSubscriber(transform, bufferSize, batchSize, child);
        } catch (Exception e) {
            Exceptions.throwIfFatal(e);
            child.onSubscribe(EmptyComponent.INSTANCE);
            child.onError(e);
            return;
        }
        source.subscribe(subscriber);
        child.onSubscribe(subscriber);
    }

    private static final class PipeOutSubscriber extends OutputStream
            implements Subscriber<byte[]>, Subscription {

        private Subscription parent;
        private SimplePlainQueue<ByteBuffer> queue = new SpscLinkedArrayQueue<ByteBuffer>(16);
        private final AtomicInteger wip = new AtomicInteger();
        private final AtomicLong requested = new AtomicLong();
        private long emitted;
        private final OutputStream out;
        private final Subscriber<? super byte[]> child;
        private boolean done;
        private final int batchSize;
        private volatile boolean cancelled;
        private int count;
        private Throwable error;
        private volatile boolean finished;

        public PipeOutSubscriber(Function<OutputStream, OutputStream> transform, int bufferSize,
                int batchSize, Subscriber<? super byte[]> child) throws Exception {
            this.batchSize = batchSize;
            this.count = batchSize - 1;
            this.child = child;
            if (bufferSize == 0) {
                this.out = transform.apply(this);
            } else {
                this.out = transform.apply(new BufferedOutputStream(this, bufferSize));
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.parent = s;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.add(requested, n);
                drain();
            }
        }

        @Override
        public void onNext(byte[] b) {
            if (done) {
                return;
            }
            count++;
            try {
                out.write(b);
            } catch (IOException ex) {
                child.onError(ex);
                return;
            }
            drain();
        }

        @Override
        public void onError(Throwable e) {
            if (done) {
                RxJavaPlugins.onError(e);
                return;
            }
            try {
                out.close();
            } catch (IOException ex) {
                // TODO build a composite exception
                RxJavaPlugins.onError(ex);
            }
            done = true;
            error = e;
            finished = true;

            drain();
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            try {
                out.close();
            } catch (IOException e) {
                onError(e);
                return;
            }
            done = true;
            drain();
        }

        private void drain() {
            if (wip.getAndIncrement() == 0) {
                int missed = 1;
                while (true) {
                    long r = requested.get();
                    long e = emitted;
                    while (e != r) {
                        if (cancelled) {
                            queue.clear();
                            return;
                        }
                        boolean d = finished;
                        ByteBuffer b = queue.poll();
                        if (b == null) {
                            if (d) {
                                // TODO support shortcutting errors
                                if (error != null) {
                                    child.onError(error);
                                } else {
                                    child.onComplete();
                                }
                                return;
                            } else {
                                count++;
                                if (count == batchSize) {
                                    count = 0;
                                    parent.request(batchSize);
                                }
                            }
                            break;
                        } else {
                            byte[] a = toArray(b);
                            child.onNext(a);
                            e++;
                        }
                    }
                    emitted = e;
                    missed = wip.addAndGet(-missed);
                    if (missed == 0) {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            finished = true;
            drain();
        }

        @Override
        public void cancel() {
            cancelled = true;
            parent.cancel();
        }

        @Override
        public void write(int b) throws IOException {
            queue.offer(ByteBuffer.allocate(1));
            drain();
        }

        @Override
        public void write(byte[] b) throws IOException {
            queue.offer(ByteBuffer.wrap(b));
            drain();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            queue.offer(ByteBuffer.wrap(b, off, len));
            drain();
        }

    }

    private static byte[] toArray(ByteBuffer b) {
        if (b.hasArray() && b.remaining() == b.array().length) {
            return b.array();
        } else {
            byte[] a = new byte[b.remaining()];
            b.get(a);
            return a;
        }
    }
}