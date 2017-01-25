package com.github.davidmoten.rx2.internal.flowable;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.reactivex.Flowable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.BiPredicate;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.internal.util.BackpressureHelper;
import io.reactivex.plugins.RxJavaPlugins;

public final class FlowableCollectWhile<T, R> extends Flowable<R> {

	private final Flowable<T> source;
	private final Callable<R> collectionFactory;
	private final BiFunction<? super R, ? super T, ? extends R> add;
	private final BiPredicate<? super R, ? super T> condition;
	private final boolean emitRemainder;

	public FlowableCollectWhile(Flowable<T> source, Callable<R> collectionFactory,
	        BiFunction<? super R, ? super T, ? extends R> add, BiPredicate<? super R, ? super T> condition,
	        boolean emitRemainder) {
		super();
		this.source = source;
		this.collectionFactory = collectionFactory;
		this.add = add;
		this.condition = condition;
		this.emitRemainder = emitRemainder;
	}

	@Override
	protected void subscribeActual(Subscriber<? super R> child) {
		CollectWhileSubscriber<T, R> subscriber = new CollectWhileSubscriber<T, R>(collectionFactory, add, condition,
		        child, emitRemainder);
		source.subscribe(subscriber);
	}

	@SuppressWarnings("serial")
	private static final class CollectWhileSubscriber<T, R> extends AtomicInteger
	        implements Subscriber<T>, Subscription {

		private final Callable<R> collectionFactory;
		private final BiFunction<? super R, ? super T, ? extends R> add;
		private final BiPredicate<? super R, ? super T> condition;
		private final Subscriber<? super R> child;
		private final boolean emitRemainder;
		private final AtomicLong requested = new AtomicLong();
		private final Queue<R> queue = new ConcurrentLinkedQueue<R>();

		private Subscription parent;
		private volatile R collection;
		private volatile boolean done;
		private Throwable error;

		private volatile boolean cancelled;

		CollectWhileSubscriber(Callable<R> collectionFactory, BiFunction<? super R, ? super T, ? extends R> add,
		        BiPredicate<? super R, ? super T> condition, Subscriber<? super R> child, boolean emitRemainder) {
			this.collectionFactory = collectionFactory;
			this.add = add;
			this.condition = condition;
			this.child = child;
			this.emitRemainder = emitRemainder;
		}

		@Override
		public void onSubscribe(Subscription parent) {
			this.parent = parent;
			child.onSubscribe(this);
		}

		@Override
		public void onNext(T t) {
			if (done) {
				return;
			}
			if (collection == null && !collectionCreated()) {
				return;
			}
			boolean collect;
			try {
				collect = condition.test(collection, t);
			} catch (Throwable e) {
				Exceptions.throwIfFatal(e);
				onError(e);
				return;
			}
			if (!collect) {
				queue.offer(collection);
				if (!collectionCreated()) {
					return;
				}
			} else {
				parent.request(1);
			}
			try {
				collection = add.apply(collection, t);
				if (collection == null) {
					throw new NullPointerException("add function should not return null");
				}
			} catch (Exception e) {
				Exceptions.throwIfFatal(e);
				onError(e);
				return;
			}
			drain();
		}

		public boolean collectionCreated() {
			try {
				collection = collectionFactory.call();
				if (collection == null) {
					throw new NullPointerException("collectionFactory should not return null");
				}
				return true;
			} catch (Exception e) {
				Exceptions.throwIfFatal(e);
				onError(e);
				return false;
			}
		}

		@Override
		public void onError(Throwable e) {
			if (done) {
				RxJavaPlugins.onError(e);
				return;
			}
			done = true;
			error = e;
			drain();
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			drain();
		}

		private void drain() {
			int missed = 1;
			if (getAndIncrement() == 0) {
				while (true) {
					long r = requested.get();
					long e = 0;
					while (e != r) {
						if (cancelled) {
							// TODO GC Nepotism?
							queue.clear();
							collection = null;
							return;
						}
						R c = queue.poll();
						if (c == null) {
							if (done) {
								if (error != null) {
									Throwable err = error;
									error = null;
									child.onError(err);
									return;
								} else {
									R col = collection;
									if (col != null) {
										collection = null;
										// ensure that the remainder is emitted
										if (emitRemainder) {
											queue.offer(col);
										}
										// loop around again
									} else {
										child.onComplete();
										return;
									}
								}
							} else {
								// nothing to emit and not done
								break;
							}
						} else {
							child.onNext(c);
							e++;
						}
					}
					if (e > 0) {
						BackpressureHelper.add(requested, -e);
					}
					missed = addAndGet(-missed);
					if (missed == 0) {
						return;
					}
				}
			}
		}

		@Override
		public void request(long n) {
			if (SubscriptionHelper.validate(n)) {
				BackpressureHelper.add(requested, n);
				parent.request(n);
				drain();
			}
		}

		@Override
		public void cancel() {
			cancelled = true;
			parent.cancel();
		}

	}
}