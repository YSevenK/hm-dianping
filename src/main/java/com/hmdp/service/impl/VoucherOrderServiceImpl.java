package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 秒杀尚未开始
            return Result.fail("秒杀尚未开始！");
        }

        // 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 秒杀结束
            return Result.fail("秒杀已经结束！");
        }

        // 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 法一
        // 悲观锁加在用户id上
        // intern方法确保用户id值一样时锁一样从而使锁有效
        // synchronized (userId.toString().intern()) {
        // 内部方法调用不经过代理对象导致事务注解不起作用
        // 获取代理对象调用
        // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        // return proxy.createVoucherOrder(voucherId);
        //}

        // 法二 分布式锁
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 获取锁
        boolean isLock = lock.tryLock();

        // 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }

        try {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 一人一单
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

        // 判断是否存在
        if (count > 0) {
            // 用户已经购买过
            return Result.fail("该用户已经购买过了");
        }

        // 扣减库存
        // 乐观锁解决超卖问题
        boolean success = seckillVoucherService.update()
                // set stock = stock - 1
                .setSql("stock=stock-1")
                // where id = ? and stock > 0
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        // 订单id
        long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 用户id
        voucherOrder.setUserId(userId);

        // 代金券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        // 返回订单id
        return Result.ok(orderId);
    }
}
