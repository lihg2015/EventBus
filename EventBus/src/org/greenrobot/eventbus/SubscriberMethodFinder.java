/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;
    //忽略方法
    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    //订阅者的class为key，所有的订阅方法对象集合为value的缓存
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();
    //索引集合，暂时不关心他的结构
    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    //是否方法严格校验
    private final boolean strictMethodVerification;
    //是否忽略索引
    private final boolean ignoreGeneratedIndex;
    //对象池个数
    private static final int POOL_SIZE = 4;
    //对象池
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];
    //默认构造函数
    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }
    //核心方法:
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        //从缓存中查找
        List<SubscriberMethod> subscriberMethods = null;//METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        //从缓存中没有找到
        if (ignoreGeneratedIndex) {
            //忽略索引
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            //使用索引
            subscriberMethods = findUsingInfo(subscriberClass);
        }

        if (subscriberMethods.isEmpty()) {
            //没有找到订阅方法　抛出异常
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            //增加到缓存并返回集合
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findState.subscriberInfo = getSubscriberInfo(findState);
            if (findState.subscriberInfo != null) {
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                findUsingReflectionInSingleClass(findState);
            }
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        findState.recycle();
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        return subscriberMethods;
    }

    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    private SubscriberInfo getSubscriberInfo(FindState findState) {
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        //获取查找对象
        FindState findState = prepareFindState();
        //初始化
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findUsingReflectionInSingleClass(findState);
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            methods = findState.clazz.getMethods();
            findState.skipSuperClasses = true;
        }
        for (Method method : methods) {
            int modifiers = method.getModifiers();
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    if (subscribeAnnotation != null) {
                        Class<?> eventType = parameterTypes[0];
                        if (findState.checkAdd(method, eventType)) {
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    static class FindState {
        //所有订阅方法的集合
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        //事件类型是已经存在的标识map
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        //方法key和方法所属的类组成的标识map
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        //产生方法key的builder
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        Class<?> subscriberClass;
        Class<?> clazz;
        boolean skipSuperClasses;
        SubscriberInfo subscriberInfo;

        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            //当前事件类型和方法是否已经添加了
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
                //没有添加,返回true
                return true;
            } else {
                //已经添加了,判断前一个添加的标识对象是不是方法对象
                if (existing instanceof Method) {
                    //是否已经添加了
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    anyMethodByEventType.put(eventType, this);
                }
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());

            String methodKey = methodKeyBuilder.toString();
            Class<?> methodClass = method.getDeclaringClass();
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                /** Skip system classes, this just degrades performance. */
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") || clazzName.startsWith("android.")) {
                    clazz = null;
                }
            }
        }
    }

}
