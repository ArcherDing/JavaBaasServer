package com.javabaas.server.object.dao.impl.mongo;

import com.javabaas.server.common.entity.SimpleCode;
import com.javabaas.server.common.entity.SimpleError;
import com.javabaas.server.object.dao.IDao;
import com.javabaas.server.object.entity.*;
import com.javabaas.server.object.entity.error.DuplicateKeyError;
import com.mongodb.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * mongoDb的对象存储实现
 */
@Component
public class MongoDao implements IDao {

    private MongoDbFactory mongo;
    private Map<String, DB> dbMap;

    @Autowired
    public MongoDao(MongoDbFactory mongo) {
        this.mongo = mongo;
        dbMap = new Hashtable<>();
    }

    private DB db(String appId) {
        String name = getDbName(appId);
        DB db = dbMap.get(name);
        if (db == null) {
            db = mongo.getDb(name);
            dbMap.put(name, db);
        }
        return db;
    }

    private String getDbName(String appId) {
        return "baas" + "_" + appId;
    }

    /**
     * 获取数据集合
     *
     * @param className 类名
     * @return DBCollection
     */
    private DBCollection getCollection(String appId, String className) {
        return db(appId).getCollection("data_" + className);
    }

    @Override
    public BaasObject insert(String appId, String className, BaasObject object) {
        DBCollection c = getCollection(appId, className);
        DBObject dbo = obj2dbo(object, false);
        try {
            c.insert(dbo);
        } catch (DuplicateKeyException e) {
            //唯一索引字段重复
            throw new DuplicateKeyError("");
        }
        return null;
    }

    @Override
    public BaasObject findOne(String appId, String className, BaasQuery query) {
        DBCollection c = getCollection(appId, className);
        BasicDBObject queryObject = new BasicDBObject(query);
        BasicDBObject dbo = (BasicDBObject) c.findOne(queryObject);
        if (dbo != null) {
            return dbo2obj(dbo);
        } else {
            return null;
        }
    }

    @Override
    public List<BaasObject> find(String appId, String className, BaasQuery query, BaasList keys, BaasSort sort, Integer limit, Integer
            skip) {
        DBCollection c = getCollection(appId, className);
        DBCursor cursor;
        BasicDBObject queryObject = new BasicDBObject(query);
        if (keys != null && keys.size() > 0) {
            //筛选返回字段
            BasicDBObject projection = new BasicDBObject();
            keys.forEach(key -> projection.put((String) key, 1));
            cursor = c.find(queryObject, projection);
        } else {
            //返回所有字段
            cursor = c.find(queryObject);
        }
        BasicDBObject sortObject = new BasicDBObject(sort);
        cursor.sort(sortObject);
        if (limit != null) {
            cursor.limit(limit);
        }
        if (skip != null) {
            cursor.skip(skip);
        }
        List<BaasObject> results = new LinkedList<>();
        try {
            cursor.forEach(dbo -> results.add(dbo2obj((BasicDBObject) dbo)));
        } catch (MongoException exception) {
            if (exception.getCode() == 17287) {
                //排序字段错误
                throw new SimpleError(SimpleCode.OBJECT_QUERY_ERROR);
            } else {
                throw exception;
            }
        }
        return results;
    }

    @Override
    public void update(String appId, String className, BaasQuery query, BaasObject object) {
        DBCollection c = getCollection(appId, className);
        DBObject dbo = obj2dbo(object, true);
        try {
            c.update(new BasicDBObject(query), dbo);
        } catch (DuplicateKeyException e) {
            //唯一索引字段重复
            throw new DuplicateKeyError("");
        }
    }

    @Override
    public void findAndModify(String appId, String className, BaasQuery query, BaasObject object) {
        DBCollection c = getCollection(appId, className);
        DBObject dbo = obj2dbo(object, true);
        try {
            DBObject result = c.findAndModify(new BasicDBObject(query), dbo);
            if (result == null) {
                //更新失败 条件不满足 抛出异常
                SimpleError.e(SimpleCode.OBJECT_FIND_AND_MODIFY_FAILED);
            }
        } catch (DuplicateKeyException e) {
            //唯一索引字段重复
            throw new DuplicateKeyError("");
        }
    }

    @Override
    public void remove(String appId, String className, BaasQuery query) {
        DBCollection c = getCollection(appId, className);
        BasicDBObject queryObject = new BasicDBObject(query);
        c.remove(queryObject);
    }

    @Override
    public void removeApp(String appId) {
        db(appId).dropDatabase();
    }

    /**
     * 删除类
     *
     * @param appId     应用名称
     * @param className 类名称
     */
    @Override
    public void removeClass(String appId, String className) {
        DBCollection c = getCollection(appId, className);
        c.drop();
    }

    @Override
    public void removeField(String appId, String className, String fieldName) {
        DBCollection c = getCollection(appId, className);
        c.updateMulti(new BasicDBObject(), new BasicDBObject().append("$unset", new BasicDBObject(fieldName, "")));
    }

    @Override
    public long count(String appId, String className, BaasQuery query) {
        //查询条件
        BasicDBObject queryObject = null;
        if (query != null) {
            queryObject = new BasicDBObject(query);
        }
        DBCollection c = getCollection(appId, className);
        long count;
        if (queryObject == null) {
            count = c.count();
        } else {
            count = c.count(queryObject);
        }
        return count;
    }

    private DBObject obj2dbo(BaasObject object, boolean isUpdate) {
        BasicDBObject dbo = new BasicDBObject();
        //存储所有操作符
        BasicDBObject set = new BasicDBObject();
        BasicDBObject unset = new BasicDBObject();
        BasicDBObject inc = new BasicDBObject();
        BasicDBObject mul = new BasicDBObject();
        BasicDBObject add = new BasicDBObject();
        BasicDBObject addUnique = new BasicDBObject();
        BasicDBObject remove = new BasicDBObject();

        Set<Map.Entry<String, Object>> entries = object.entrySet();
        for (Map.Entry<String, Object> entry : entries) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value != null) {
                if (value instanceof BaasOperator) {
                    //操作符
                    BaasOperator operator = (BaasOperator) value;
                    switch (operator.getType()) {
                        case DELETE:
                            unset.put(key, 1);
                            break;
                        case INCREMENT:
                            inc.put(key, operator.getValue());
                            break;
                        case MULTIPLY:
                            mul.put(key, operator.getValue());
                            break;
                        case ADD:
                            BaasList addList = (BaasList) operator.getValue();
                            BasicDBList dbList = new BasicDBList();
                            dbList.addAll(addList);
                            add.put(key, dbList);
                            break;
                        case ADD_UNIQUE:
                            BaasList uniqueList = (BaasList) operator.getValue();
                            dbList = new BasicDBList();
                            dbList.addAll(uniqueList);
                            addUnique.put(key, new BasicDBObject("$each", dbList));
                            break;
                        case REMOVE:
                            BaasList removeList = (BaasList) operator.getValue();
                            dbList = new BasicDBList();
                            dbList.addAll(removeList);
                            remove.put(key, dbList);
                            break;
                    }
                } else {
                    //非操作符
                    if (isUpdate) {
                        //更新时将数据放入$set中
                        set.put(key, value);
                    } else {
                        dbo.put(key, value);
                    }
                }
            }
        }
        if (isUpdate) {
            if (set.size() > 0) {
                dbo.put("$set", set);
            }
            if (unset.size() > 0) {
                dbo.put("$unset", unset);
            }
            if (inc.size() > 0) {
                dbo.put("$inc", inc);
            }
            if (mul.size() > 0) {
                dbo.put("$mul", mul);
            }
            if (add.size() > 0) {
                dbo.put("$pushAll", add);
            }
            if (addUnique.size() > 0) {
                dbo.put("$addToSet", addUnique);
            }
            if (remove.size() > 0) {
                dbo.put("$pullAll", remove);
            }
        }
        return dbo;
    }

    private DBObject obj2inc(BaasObject object) {
        BasicDBObject dbo = new BasicDBObject();
        BasicDBObject set = new BasicDBObject();
        BasicDBObject inc = new BasicDBObject();
        Set<Map.Entry<String, Object>> entries = object.entrySet();
        for (Map.Entry<String, Object> entry : entries) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value != null && !key.equals("updatedAt") && !key.equals("updatedPlat")) {
                inc.put(key, value);
            }
        }
        set.put("updatedAt", object.get("updatedAt"));
        set.put("updatedPlat", object.getUpdatedPlatform());
        dbo.put("$set", set);
        dbo.put("$inc", inc);
        return dbo;
    }

    private BaasObject dbo2obj(BasicDBObject dbo) {
        //简单的拷贝数据至BaasObject
        BaasObject object = new BaasObject();
        Set<Map.Entry<String, Object>> entries = dbo.entrySet();
        for (Map.Entry<String, Object> entry : entries) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof BasicDBObject) {
                object.put(key, new BaasObject((BasicDBObject) value));
            } else if (value instanceof BasicDBList) {
                object.put(key, new BaasList((BasicDBList) value));
            } else {
                object.put(key, value);
            }
        }
        return object;
    }

}