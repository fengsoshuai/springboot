/*
 * Copyright (c) 2020. zhanghan_java@163.com All Rights Reserved.
 * 项目名称：Spring Boot实战分页查询附近的人: Redis+GeoHash+Lua
 * 类名称：GeoServiceImpl.java
 * 创建人：张晗
 * 联系方式：zhanghan_java@163.com
 * 开源地址: https://github.com/dangnianchuntian/springboot
 * 博客地址: https://zhanghan.blog.csdn.net
 */

package com.zhanghan.zhnearbypeople.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import com.zhanghan.zhnearbypeople.controller.request.ListNearByPeopleRequest;
import com.zhanghan.zhnearbypeople.controller.request.PostGeoRequest;
import com.zhanghan.zhnearbypeople.dto.NearByPeopleDto;
import com.zhanghan.zhnearbypeople.service.GeoService;
import com.zhanghan.zhnearbypeople.util.RedisLuaUtil;
import com.zhanghan.zhnearbypeople.util.wrapper.WrapMapper;

@Service
public class GeoServiceImpl implements GeoService {

    private static Logger logger = LoggerFactory.getLogger(GeoServiceImpl.class);

    @Autowired
    private RedisTemplate<String, Object> objRedisTemplate;

    @Value("${zh.geo.redis.key:zhgeo}")
    private String zhGeoRedisKey;

    @Value("${zh.geo.zset.redis.key:zhgeozset:}")
    private String zhGeoZsetRedisKey;

    /**
     * 记录用户访问记录
     */
    @Override
    public Object postGeo(PostGeoRequest postGeoRequest) {

        //对应redis原生命令:GEOADD zhgeo 116.48105 39.996794 zhangsan
        Long flag = objRedisTemplate.opsForGeo().add(zhGeoRedisKey, new RedisGeoCommands.GeoLocation<>(postGeoRequest
                .getCustomerId(), new Point(postGeoRequest.getLatitude(), postGeoRequest.getLongitude())));

        if (null != flag && flag > 0) {
            return WrapMapper.ok();
        }

        return WrapMapper.error();
    }

    /**
     * 分页查询附近的人
     */
    @Override
    public Object listNearByPeople(ListNearByPeopleRequest listNearByPeopleRequest) {

        String customerId = listNearByPeopleRequest.getCustomerId();

        String strZsetUserKey = zhGeoZsetRedisKey + customerId;

        List<NearByPeopleDto> nearByPeopleDtoList = new ArrayList<>();

        //如果是从第1页开始查,则将附近的人写入zset集合，以后页直接从zset中查
        if (1 == listNearByPeopleRequest.getPageIndex()) {
            List<String> scriptParams = new ArrayList<>();
            scriptParams.add(zhGeoRedisKey);
            scriptParams.add(customerId);
            scriptParams.add("100");
            scriptParams.add(RedisGeoCommands.DistanceUnit.KILOMETERS.getAbbreviation());
            scriptParams.add("asc");
            scriptParams.add("storedist");
            scriptParams.add(strZsetUserKey);

            //用Lua脚本实现georadiusbymember中的storedist参数
            //对应Redis原生命令:georadiusbymember zhgeo sunliu 100 km asc count 5 storedist sunliu
            Long executeResult = objRedisTemplate.execute(RedisLuaUtil.GEO_RADIUS_STOREDIST_SCRIPT(), scriptParams);

            if (null == executeResult || executeResult < 1) {
                return WrapMapper.ok(nearByPeopleDtoList);
            }

            //zset集合中去除自己
            //对应Redis原生命令:zrem sunliu sunliu
            Long remove = objRedisTemplate.opsForZSet().remove(strZsetUserKey, customerId);

        }

        nearByPeopleDtoList = listNearByPeopleFromZset(strZsetUserKey, listNearByPeopleRequest.getPageIndex(),
                listNearByPeopleRequest.getPageSize());

        return WrapMapper.ok(nearByPeopleDtoList);

    }

    /**
     * 分页从zset中查询指定用户附近的人
     */
    private List<NearByPeopleDto> listNearByPeopleFromZset(String strZsetUserKey, Integer pageIndex, Integer pageSize) {

        Integer startPage = (pageIndex - 1) * pageSize;
        Integer endPage = pageIndex * pageSize - 1;
        List<NearByPeopleDto> nearByPeopleDtoList = new ArrayList<>();
        //对应Redis原生命令:zrange key 0 2 withscores
        Set<ZSetOperations.TypedTuple<Object>> zsetUsers = objRedisTemplate.opsForZSet()
                .rangeWithScores(strZsetUserKey, startPage,
                        endPage);

        for (ZSetOperations.TypedTuple<Object> zsetUser : zsetUsers) {
            NearByPeopleDto nearByPeopleDto = new NearByPeopleDto();
            nearByPeopleDto.setCustomerId(zsetUser.getValue().toString());
            nearByPeopleDto.setDistance(zsetUser.getScore());
            nearByPeopleDtoList.add(nearByPeopleDto);
        }

        return nearByPeopleDtoList;
    }

}
