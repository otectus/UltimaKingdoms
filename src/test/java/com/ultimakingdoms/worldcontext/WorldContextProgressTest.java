package com.ultimakingdoms.worldcontext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldContextProgressTest {
    @Test void eachPlayersOwnPassesCoverTheMaximumRouteSetWithoutCrossPlayerState(){
        int journeys=2048;boolean[] visited=new boolean[journeys];
        for(int pass=0;pass<journeys/8;pass++){
            int ticks=pass*20,start=WorldContextService.progressStart(ticks,journeys);
            assertEquals(start,WorldContextService.progressStart(ticks,journeys),"another player on the same pass must get an independent identical window");
            for(int i=0;i<8;i++)visited[(start+i)%journeys]=true;
        }
        for(int i=0;i<journeys;i++)assertTrue(visited[i],"journey "+i+" starved");
    }
}
