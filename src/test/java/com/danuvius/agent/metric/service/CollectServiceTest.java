package com.danuvius.agent.metric.service;

import com.alibaba.fastjson.JSON;
import com.danuvius.agent.metric.model.MetricData;
import com.danuvius.agent.redis.collector.RedisInfoCollector;


public class CollectServiceTest {

    public static void main(String[] args) {
        RedisInfoCollector collector=new RedisInfoCollector();
        MetricData metric = collector.collect();
        System.out.println("采集结果: " + JSON.toJSONString(metric));
    }
}
