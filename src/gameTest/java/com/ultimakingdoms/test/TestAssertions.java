package com.ultimakingdoms.test;
import java.util.Objects;
public final class TestAssertions {
    public static void assertEquals(Object a,Object b){if(!Objects.equals(a,b))throw new net.minecraft.gametest.framework.GameTestAssertException("Expected "+a+" but was "+b);}
    public static void assertTrue(boolean a){if(!a)throw new net.minecraft.gametest.framework.GameTestAssertException("Expected true");}
    public static void assertFalse(boolean a){if(a)throw new net.minecraft.gametest.framework.GameTestAssertException("Expected false");}
    public static void assertInstanceOf(Class<?> type,Object o){if(!type.isInstance(o))throw new net.minecraft.gametest.framework.GameTestAssertException("Expected "+type+" but was "+o);}
    public interface Throwing {void run()throws Throwable;}
    public static <T extends Throwable>T assertThrows(Class<T> type,Throwing action){try{action.run();}catch(Throwable t){if(type.isInstance(t))return type.cast(t);throw new net.minecraft.gametest.framework.GameTestAssertException("Wrong failure: "+t);}throw new net.minecraft.gametest.framework.GameTestAssertException("Expected "+type);}
}
