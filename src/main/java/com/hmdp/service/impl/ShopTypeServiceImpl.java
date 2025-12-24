package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 从redis查询商铺缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        // 判断缓存是否存在
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 缓存存在：反序列化为List<ShopType>并返回
            List<ShopType> typeList = JSONUtil.toBean(
                    shopTypeJson,
                    new TypeReference<List<ShopType>>() {
                    }, // 处理List泛型反序列化
                    true
            );
            return Result.ok(typeList);
        }

        // 缓存不存在：查询数据库（按sort升序）
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 数据库查询为空：返回失败提示
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("商户类型列表为空");
        }

        // 数据库查询成功：将结果写入Redis（带过期时间）
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_TYPE_KEY,
                JSONUtil.toJsonStr(typeList),
                CACHE_SHOP_TYPE_TTL,
                TimeUnit.MINUTES
        );

        // 返回查询结果
        return Result.ok(typeList);
    }
}
