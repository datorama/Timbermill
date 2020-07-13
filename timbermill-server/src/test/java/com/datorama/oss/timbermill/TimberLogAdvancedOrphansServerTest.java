package com.datorama.oss.timbermill;


import org.junit.BeforeClass;
import org.junit.Test;

public class TimberLogAdvancedOrphansServerTest extends TimberLogAdvancedOrphansTest{

	@BeforeClass
	public static void init() {
		TimberLogServerTest.init();
	}

	@Test
	public void testOrphanIncorrectOrder() {
		super.testOrphanIncorrectOrder();
	}

	@Test
	public void testOrphanWithAdoption(){
		super.testOrphanWithAdoption();
	}

	@Test
	public void testOrphanWithAdoptionParentWithNoStartDifferentBatch(){
		super.testOrphanWithAdoptionParentWithNoStartDifferentBatch();
	}

	@Test
	public void testOrphanWithAdoptionParentWithNoStart(){
		super.testOrphanWithAdoptionParentWithNoStart();
	}

	@Test
	public void testOrphanWithComplexAdoption(){
		super.testOrphanWithComplexAdoption();
	}

	@Test
	public void testOutOfOrderComplexOrphanWithAdoption(){
		super.testOutOfOrderComplexOrphanWithAdoption();
	}

	@Test
	public void testInOrderComplexOrphanWithAdoption(){
		super.testInOrderComplexOrphanWithAdoption();
	}

	@Test
	public void testOrphanWithAdoptionDifferentBatches(){
		super.testOrphanWithAdoptionDifferentBatches();
	}

	@Test
	public void testStringOfOrphans(){
		super.testStringOfOrphans();
	}

	@Test
	public void testOrphanParentInexedOnOtherNodeSuccess() {super.testOrphanParentInexedOnOtherNodeSuccess(); }
}
