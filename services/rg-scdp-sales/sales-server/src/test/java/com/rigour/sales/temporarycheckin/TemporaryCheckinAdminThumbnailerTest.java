package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 缩略图的尺寸、源字节和并发边界；构造小文件头模拟超大图，避免测试自身分配大位图。 */
class TemporaryCheckinAdminThumbnailerTest {
    @Test
    void rejectsDeclaredOversizeBeforeOpeningObject() {
        AtomicInteger opened=new AtomicInteger();
        var media=new TemporaryCheckinService.AdminMedia(()-> {
            opened.incrementAndGet();return new ByteArrayInputStream(new byte[0]);
        },16L*1024*1024+1,"image/jpeg","large.jpg");
        assertThatThrownBy(()->new TemporaryCheckinAdminThumbnailer().create(media))
                .isInstanceOfSatisfying(TemporaryCheckinException.class,error->
                        assertThat(error.code()).isEqualTo("TEMP_CHECKIN_IMAGE_LIMIT"));
        assertThat(opened.get()).isZero();
    }

    @Test
    void rejectsHugeDimensionsFromHeaderBeforeDecodingPixels() throws Exception {
        for(int[] dimensions:new int[][] {{20001,1},{10001,10000}}) {
            byte[] source=png(2,2);
            ByteBuffer.wrap(source,16,8).putInt(dimensions[0]).putInt(dimensions[1]);
            CRC32 crc=new CRC32();crc.update(source,12,17);
            ByteBuffer.wrap(source,29,4).putInt((int)crc.getValue());
            var media=media(source,"image/png");
            assertThatThrownBy(()->new TemporaryCheckinAdminThumbnailer().create(media))
                    .isInstanceOfSatisfying(TemporaryCheckinException.class,error->
                            assertThat(error.code()).isEqualTo("TEMP_CHECKIN_IMAGE_LIMIT"));
        }
    }

    @Test
    void decodesOnlyOneImageAtATimeAndRestoresSlotAfterFailure() throws Exception {
        var decoder=new TemporaryCheckinAdminThumbnailer();
        byte[] source=png(960,640);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var blocked=new TemporaryCheckinService.AdminMedia(()-> {
            entered.countDown();
            try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
            return new ByteArrayInputStream(source);
        },source.length,"image/png","source.png");
        var first=CompletableFuture.supplyAsync(()->decoder.create(blocked));
        CompletableFuture<TemporaryCheckinAdminThumbnailer.Thumbnail> waiting=null;
        AtomicInteger opened=new AtomicInteger();
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var second=new TemporaryCheckinService.AdminMedia(()-> {
                opened.incrementAndGet();return new ByteArrayInputStream(source);
            },source.length,"image/png","second.png");
            CountDownLatch waitingStarted=new CountDownLatch(1);
            waiting=CompletableFuture.supplyAsync(()-> {waitingStarted.countDown();return decoder.create(second);});
            assertThat(waitingStarted.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(waiting.isDone()).isFalse();
            assertThat(opened.get()).isZero();
        } finally { release.countDown(); }
        var result=ImageIO.read(new ByteArrayInputStream(first.get(5,TimeUnit.SECONDS).bytes()));
        assertThat(waiting.get(5,TimeUnit.SECONDS).bytes()).isNotEmpty();
        assertThat(opened.get()).isEqualTo(1);
        assertThat(result.getWidth()).isEqualTo(320);
        assertThat(result.getHeight()).isLessThanOrEqualTo(320);
        assertThatThrownBy(()->decoder.create(media(new byte[]{1,2,3},"image/heic")))
                .isInstanceOfSatisfying(TemporaryCheckinException.class,error->
                        assertThat(error.code()).isEqualTo("TEMP_CHECKIN_IMAGE_UNSUPPORTED"));
        assertThat(decoder.create(media(source,"image/png")).bytes()).isNotEmpty();
    }

    @Test
    void interruptedWaitPreservesInterruptAndDoesNotClaimOrRead() throws Exception {
        var decoder=new TemporaryCheckinAdminThumbnailer();
        AtomicInteger claimed=new AtomicInteger(),opened=new AtomicInteger();
        var source=new TemporaryCheckinService.AdminMedia(()-> {
            opened.incrementAndGet();return new ByteArrayInputStream(new byte[0]);
        },100,"image/png","source.png");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptKept=new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread=new Thread(()-> {
            Thread.currentThread().interrupt();
            try {decoder.create(source,claimed::incrementAndGet);}
            catch(Throwable error) {failure.set(error);interruptKept.set(Thread.currentThread().isInterrupted());}
        });
        thread.start();thread.join(1000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOfSatisfying(TemporaryCheckinException.class,error->
                assertThat(error.code()).isEqualTo("TEMP_CHECKIN_IMAGE_BUSY"));
        assertThat(interruptKept.get()).isTrue();
        assertThat(claimed.get()).isZero();assertThat(opened.get()).isZero();
        assertThat(decoder.create(media(png(32,32),"image/png")).bytes()).isNotEmpty();
    }

    @Test
    void boundsActualReadEvenWhenStoredSizeClaimsSmallObject() throws Exception {
        byte[] normal=png(1,1);
        ByteArrayOutputStream prefix=new ByteArrayOutputStream();
        prefix.write(normal,0,33);
        prefix.write(ByteBuffer.allocate(4).putInt(17*1024*1024).array());
        prefix.write(new byte[]{'t','E','X','t'});
        java.util.concurrent.atomic.AtomicLong read=new java.util.concurrent.atomic.AtomicLong();
        var body=new java.io.InputStream() {
            @Override public int read() { read.incrementAndGet();return 0; }
            @Override public int read(byte[] bytes,int offset,int length) {
                java.util.Arrays.fill(bytes,offset,offset+length,(byte)0);read.addAndGet(length);return length;
            }
        };
        var source=new TemporaryCheckinService.AdminMedia(()->new java.io.SequenceInputStream(
                new ByteArrayInputStream(prefix.toByteArray()),body),100,"image/png","misstated.png");
        assertThatThrownBy(()->new TemporaryCheckinAdminThumbnailer().create(source))
                .isInstanceOf(TemporaryCheckinException.class);
        assertThat(read.get()).isBetween(15L*1024*1024,16L*1024*1024+1);
    }

    private static TemporaryCheckinService.AdminMedia media(byte[] bytes,String type) {
        return new TemporaryCheckinService.AdminMedia(()->new ByteArrayInputStream(bytes),bytes.length,type,"source.png");
    }

    private static byte[] png(int width,int height) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return bytes.toByteArray();
    }
}
