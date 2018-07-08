package sdp.capacity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;

import sdp.sampling.SampleFactory;
import umontreal.ssj.probdist.DiscreteDistributionInt;
import umontreal.ssj.probdist.Distribution;
import umontreal.ssj.probdist.PoissonDist;
import umontreal.ssj.stat.TallyStore;

public class SimOpt {
	int minMonteCarloSimulationRunsQ = 100;
	int minMonteCarloSimulationRunsP = 100;
	
	double minQ = 0;
	int maxQ;
	   
	double fixOrderCost;
	double variOrderCost; 
	double penaltyCost;
	double holdingCost;
	
	Distribution[] distributions;
	
	double confidenceLevel = 0.99;
	double percentageError = 1;
	double step = 0.1;
	double iniInventory = 0.0;
	
	SampleFactory sfP = new SampleFactory();
	SampleFactory sfQ = new SampleFactory();
	   
	public SimOpt(double fixOrderCost, double variOrderCost, double penaltyCost, 
			double holdingCost, Distribution[] distributions, int maxOrderQuantity) {
		this.fixOrderCost = fixOrderCost;
		this.variOrderCost = variOrderCost;
		this.penaltyCost = penaltyCost;
		this.holdingCost = holdingCost;
		this.distributions = distributions;
		this.maxQ = maxOrderQuantity;
		
		sfP.resetStartStream();
	}
	
	public double orderingCost(double Q){
		return Q > 0 ? this.fixOrderCost + Q * this.variOrderCost : 0;
	}
	
	public double inventoryCost(double inventory){
		return this.holdingCost*Math.max(inventory, 0) + this.penaltyCost*Math.max(-inventory, 0);
	}
	
	public double simulateSingleRunCycle(double Q, double inventory, Distribution[] distributions){
		double[][] d = this.sfQ.getNextLHSample(distributions, 1);
		double costs = 0.0;
		for(int t = 0; t < distributions.length; t++){
			if (t == 0) {
				costs += orderingCost(Q);
				inventory += Q;
			}
			inventory -= d[t][0];
			costs += inventoryCost(inventory);
		}
		return costs;
	}
	
	public double[] simulateMultiRunCycle(double Q, double inventory, Distribution[] distributions){
		this.sfQ.resetStartStream();
		TallyStore observationsTally = new TallyStore();
		for(int runs = 0; runs < this.minMonteCarloSimulationRunsQ; runs++){
			observationsTally.add(simulateSingleRunCycle(Q, inventory, distributions));
		}
		double[] centerAndRadius = new double[2];
		observationsTally.confidenceIntervalStudent(this.confidenceLevel, centerAndRadius);
		return centerAndRadius;
	}
	
	public double getQ(int period, double inventory){
		double noOrderCosts = simulateMultiRunCycle(0, inventory, new Distribution[]{this.distributions[period]})[0];

		double bestCosts = Double.MAX_VALUE;
		double bestQ = 0;
		for(int t = 0; t < this.distributions.length - period; t++){
			Distribution[] reducedHorizon = Arrays.stream(distributions, period, period + t + 1).toArray(Distribution[]::new);

			double Qlb = minQ;
			double Qub = maxQ;

			double Q = (Qub + Qlb)/2;
			do{
				double gradient = this.simulateMultiRunCycle(Q, inventory, reducedHorizon)[0] - this.simulateMultiRunCycle(Q + step, inventory, reducedHorizon)[0];
				if(gradient > 0){
					Qlb = Q + step;
				}else{
					Qub = Q;
				}
				Q = (Qub + Qlb)/2;
			}while(
					(reducedHorizon[t] instanceof DiscreteDistributionInt && Qub - Qlb > step)
					);
			Q = Math.round(Q);

			double curCosts = (this.simulateMultiRunCycle(Q, inventory, reducedHorizon)[0])/reducedHorizon.length;
			if(curCosts < bestCosts){
				bestCosts = curCosts;
				bestQ = Q;
			}
		}

		return noOrderCosts < bestCosts ? 0 : bestQ;
	}
	
	public double simulateSingleRun(){
		double inventory = this.iniInventory;
		double costs = 0;
		double[][] d = this.sfP.getNextLHSample(this.distributions, 1);
		for(int t = 0; t < this.distributions.length; t++){
			double Q = getQ(t, inventory);
			if(Q > 0){
				costs += orderingCost(Q);
				inventory += Q;
			}
			inventory -= d[t][0];
			costs += inventoryCost(inventory);
		}
		return costs;
	}
	
	public double[] simulate(){
		sfP.resetNextSubstream();
		TallyStore observationsTally = new TallyStore();
		for(int runs = 0; runs < this.minMonteCarloSimulationRunsP; runs++){
			observationsTally.add(simulateSingleRun());
		}
		double[] centerAndRadius = new double[2];
		observationsTally.confidenceIntervalStudent(confidenceLevel, centerAndRadius);
		return centerAndRadius;
	}
	
	public static void writeToFile(String fileName, String str){
		File results = new File(fileName);
		try {
			FileOutputStream fos = new FileOutputStream(results, true);
			OutputStreamWriter osw = new OutputStreamWriter(fos);
			osw.write(str+"\n");
			osw.close();
		} 
		catch (FileNotFoundException e) {
			e.printStackTrace();
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static String getHeadersString(){
		return "K,v,h,I0,pai,Qmax, DemandPatt, OpValue, Time(sec), simValue, error";
	}
	
	public static void runSingleCase() {
		double fixOrderCost = 500; 
	    double variOrderCost = 2; 
	    double penaltyCost = 20;
	    double holdingCost = 1;
	    int maxOrderQuantity = 60;
		
		double demand[] = {20,20,20,20,20,20,20,20,20,20};     
	    Distribution[] distributions = new Distribution[demand.length];
	    for (int i = 0; i < demand.length; i++)
	    	distributions[i] = new PoissonDist(demand[i]);
	    
	    SimOpt simOpt = new SimOpt(fixOrderCost, variOrderCost, penaltyCost, holdingCost, distributions, maxOrderQuantity);
	    long currTime1=System.currentTimeMillis(); 
	    double[] stats = simOpt.simulate();
	    System.out.println(stats[0]+" "+stats[1]);
	    double time = (System.currentTimeMillis()-currTime1)/1000;
	    System.out.println("running time is " + time + " s");
	}
	
	public static void runMultiCases() {
		writeToFile("./"+CLSP.class.getSimpleName() + "_results.csv", getHeadersString());

		double[][] demands = {{20,20,20,20,20,20,20,20,20,20},
				{5.4,7.2,9.6,12.2,15.4,18.6,22,25.2,28.2,30.6},
				{33.2,32.4,30.6,28.2,25.2,22,18.6,15.4,12.2,9.6},
				{24.2,20,15.8,14,15.8,20,24.2,26,24.2,20},
				{31.4,20,8.6,4,8.6,20,31.4,36,31.4,20},
				{41.8,18.2,6.6,15.8,0.4,15.2,21.8,23,44.8,4.4},
				{0.4,10.2,30.4,93.4,53.6,97.8,89.2,49.6,56.2,72.6},
				{9.4,16.2,47.2,78.8,32.8,57.4,101.6,78.2,150.8,138.8},
				{8.8,23.2,52.8,28.8,29.2,39.6,14.8,36.6,40.8,22.8},
				{9.8,37.6,12.8,55.8,90.6,44.8,44.6,103.4,58.2,109.4},
		};


		double[] K = {2000,1000,500};
		double[] v = {2,5,10};
		double[] pai = {20,10,5};
		double[] capacity = {3, 5, 7};
		double holdingCost = 1;
		
		for (int iK = 0; iK < K.length; iK++) {
			for (int iv = 0; iv < v.length; iv++) {
				for (int ipai = 0; ipai < pai.length; ipai++) {
					for (int idemand = 0; idemand < demands.length; idemand++) 
						for ( int icapacity = 0; icapacity < capacity.length; icapacity++){	      
							double[] meanDemand = demands[idemand];
							double fixOrderCost = K[iK] ; 
							double variOrderCost = v[iv]; 
							double penaltyCost = pai[ipai];
							Distribution[] distributions = new Distribution[meanDemand.length];
							for (int i = 0; i < meanDemand.length; i++)
								distributions[i] = new PoissonDist(meanDemand[i]);
							int maxOrderQuantity = (int) (Math.round(Arrays.stream(meanDemand).sum()/meanDemand.length)*capacity[icapacity]);

							SimOpt simOpt = new SimOpt(fixOrderCost, variOrderCost, penaltyCost, holdingCost, distributions, maxOrderQuantity);
							long currTime1=System.currentTimeMillis(); 
							double[] stats = simOpt.simulate();
							System.out.println("sim-opt final value:" + stats[0]+" "+stats[1]);
							double time = (System.currentTimeMillis()-currTime1)/1000;
							System.out.println("running time is " + time + " s");

							String out = fixOrderCost+",\t"+
									variOrderCost+",\t"+
									holdingCost+",\t"+
									penaltyCost+",\t"+
									maxOrderQuantity+",\t"+
									(idemand + 1) +",\t"+
									stats[0] +",\t"+
									time + ",\t";

							writeToFile("./"+ SimOpt.class.getSimpleName() + "_results.csv", out);
						}
	    		  }
	    	  }
		}
	}
	
	public static void main(String[] args) {
		//runSingleCase();
		runMultiCases();
	}

}
