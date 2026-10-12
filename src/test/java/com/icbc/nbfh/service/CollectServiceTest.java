package com.icbc.nbfh.service;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.entity.dto.MetricData;
import com.icbc.nbfh.service.collector.RedisInfoCollector;


public class CollectServiceTest {

    public static void main(String[] args) {
        RedisInfoCollector collector=new RedisInfoCollector();
        MetricData metric = collector.collect();
        System.out.println("采集结果: " + JSON.toJSONString(metric));
    }
}
