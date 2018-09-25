package cash.strongconstraint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import com.sun.org.apache.bcel.internal.generic.INVOKEINTERFACE;

import sdp.cash.CashState;
import sdp.inventory.State;
import sun.security.provider.JavaKeyStore.CaseExactJKS;
import umontreal.ssj.probdist.Distribution;
import umontreal.ssj.probdist.PoissonDist;


/**
 * @author: Zhen Chen
 * @email: 15011074486@163.com
 * @date: Jul 14, 2018---1:23:28 PM
 * @description: find s C S for cash flow sdp
 */

public class FindsCS {
	int T;
	double iniCash;
	double[] meanD;
	double fixOrderCost;
	double price;
	double variOrderCost;
	double holdCost;
	double salvageValue;
	
	Map<State, Double> cacheCValues = new TreeMap<>(); // record C values for different initial inventory x

	public FindsCS(double iniCash, double[] meanD, double fixOrderCost, double price,
			double variOrderCost, double holdCost, double salvageValue) {
		this.T = meanD.length;
		this.iniCash = iniCash;
		this.meanD = meanD;
		this.fixOrderCost = fixOrderCost;
		this.price = price;
		this.variOrderCost = variOrderCost;
		this.holdCost = holdCost;
		this.salvageValue = salvageValue;
		Comparator<State> keyComparator = (o1, o2) -> o1.getPeriod() > o2.getPeriod() ? 1 : 
			o1.getPeriod() == o2.getPeriod() ? o1.getIniInventory() > o2.getIniInventory() ? 1 : 
				o1.getIniInventory() == o2.getIniInventory() ? 0 : -1 : -1;
		this.cacheCValues = new TreeMap<>(keyComparator);
	}
	
	public enum FindCCrieria{
		MAX,
		MIN,
		AVG,
		XRELATE;
	}

	double[][] getsCS(double[][] optimalTable, double minCashRequired, FindCCrieria criteria) {
		double[][] optimalsCS = new double[T][3];
		optimalsCS[0][0] = optimalTable[0][1] ;
		optimalsCS[0][1] = optimalTable[0][2] ;
		optimalsCS[0][2] = optimalTable[0][1] + optimalTable[0][3];	
		cacheCValues.put(new State(1, optimalTable[0][0]), optimalTable[0][1]);
				
		for (int t = 1; t < T; t++) {
			final int i = t + 1;
			int recordTimes = 0;
			double[][] tOptTable = Arrays.stream(optimalTable).filter(p -> p[0] == i)
					.map(p -> Arrays.stream(p).toArray()).toArray(double[][]::new);
			
			if (t == T - 1) {
				Distribution distribution = new PoissonDist(meanD[T - 1]);
				optimalsCS[T - 1][2] = distribution.inverseF((price - variOrderCost) / (holdCost  + price - salvageValue));
				double S = optimalsCS[T - 1][2];
				for (int j = (int) S; j >= 0; j--) {
					if (Ly(j, t) < Ly(S, t) - fixOrderCost) {
						optimalsCS[T - 1][0] = j + 1;
						break;
					}
				}
				optimalsCS[T - 1][1] = 0; // C ��Ĭ��ֵΪ 0
				for (int j = (int) S; j >= 0; j--) {
					for (int jj = j + 1; jj <= (int) S; jj++) {
						if (Ly(jj,  t) > fixOrderCost + Ly(j, t)) {
							optimalsCS[t][1] = fixOrderCost + variOrderCost * (jj - 1 - j); // C for x = 0 at last
							cacheCValues.put(new State(t + 1, j), optimalsCS[t][1]);
							break;
						}
					}
				}
				break;
			}				
			
			optimalsCS[t][2] = minCashRequired;
			optimalsCS[t][1] = 0; // default value for C is 0
			ArrayList<Double> recordCash = new ArrayList<>();
			double mark_s = 0; //backward, the first inventory level that starts ordering is s
			for (int j = tOptTable.length - 1; j >= 0; j--) {
				if (tOptTable[j][3] != 0) {
					if (mark_s == 0) {
						optimalsCS[t][0] = j + 1 < tOptTable.length ? tOptTable[j + 1][1]
																	    : tOptTable[j][1] + 1; // maximum not ordering inventory level as s
						mark_s = 1;
					}
					if (tOptTable[j][1] + tOptTable[j][3] > optimalsCS[t][2]) //  maximum order-up-to level as S
						optimalsCS[t][2] = tOptTable[j][1] + tOptTable[j][3];
					recordTimes = 1; // 
				}
								
				if (tOptTable[j][3] == 0 && recordTimes == 1) 
					recordCash.add(tOptTable[j][2]);					
			}
			// choose a maximum not ordering cash level as C when ordering quantity is 0
			// or choose an average value
			// or choose a minimum value
			// or related with x (initial inventory), this C is a lower bound
			switch (criteria) {
				case MAX:
					optimalsCS[t][1] = recordCash.stream().mapToDouble(p -> p).max().isPresent() ? recordCash.stream().mapToDouble(p -> p).max().getAsDouble() : minCashRequired;
					break;
				case MIN:
					optimalsCS[t][1] = recordCash.stream().mapToDouble(p -> p).min().isPresent() ? recordCash.stream().mapToDouble(p -> p).min().getAsDouble() : minCashRequired;
					break;
				case AVG:
					optimalsCS[t][1] = recordCash.stream().mapToDouble(p -> p).average().isPresent() ? recordCash.stream().mapToDouble(p -> p).average().getAsDouble() : minCashRequired;
				case XRELATE:
					Distribution distribution = new PoissonDist(meanD[t]);
					double S = distribution.inverseF((price - variOrderCost) / (holdCost  + price));
					for (int j = 0; j <= (int) S; j++) {
						for (int jj = j + 1; jj <= (int) S; jj++) {
							if (Ly(jj,  t) > fixOrderCost + Ly(j, t)) {
								optimalsCS[t][1] = fixOrderCost + variOrderCost * (jj - 1 - j); // C for x = 0 at last
								cacheCValues.put(new State(t + 1, j), optimalsCS[t][1]);
								break;
							}
						}
					}				
			}
		}
		System.out.println("(s, C, S) are: " + Arrays.deepToString(optimalsCS));
		return optimalsCS;
	}

	
	/**
	 * compute L(y)
	 * @param y : order-up-to level y,
	 * @param t : period t - 1
	 */
	double Ly(double y, int t) {
		double meands = t == T - 1 ? meanD[t] : meanD[t] + meanD[t + 1]; // a heuristic step
		Distribution distribution = new PoissonDist(meands);
		
		double meanI = 0;
		for (int i = 0; i < y; i++)
			meanI += (y - i) * (distribution.cdf(i + 0.5) - distribution.cdf(i - 0.5));
		
		double Ly = 0;
		if (t == T - 1)
			Ly = (price - variOrderCost) * y- (price + holdCost - salvageValue) * meanI;
		else
			Ly = (price - variOrderCost) * y- (price + holdCost) * meanI;

		return Ly;
	}
	
	/**
	 * 
	 * @return optimal C values of cacheCValues
	 */
	public double[][] getOptCTable(){
		Iterator<Map.Entry<State, Double>> iterator = cacheCValues.entrySet().iterator();
		double[][] arr = new double[cacheCValues.size()][2];
		int i = 0;
		while (iterator.hasNext()) {
			Map.Entry<State, Double> entry = iterator.next();
			arr[i++] =new double[]{entry.getKey().getPeriod(), entry.getKey().getIniInventory(), entry.getValue()};
		}
		return arr;
	}
	
	/**
	 * check whether (s, C, S) policy satisfy all states in the optimal table of sdp
	 * 
	 * @param sCS
	 * @param optTable
	 * @param fixOrderCost
	 * @param variCost
	 */
	public int checksBS(double[][] sCS, double[][] optTable, Double minCashRequired, double maxOrderQ, double fixOrderCost, double variCost) {
		int nonOptCount = 0;
		for (int t = 1; t < T; t++) {
			final int i = t + 1;
			
			double[][] tOptTable = Arrays.stream(optTable).filter(p -> p[0] == i).map(p -> Arrays.stream(p).toArray())
					.toArray(double[][]::new);
			for (int j = 0; j < tOptTable.length; j++) {
				if (tOptTable[j][1] < sCS[t][0]) {
					if (cacheCValues.get(new State(i, tOptTable[j][1])) == null)
						sCS[t][1] = 0;
					else
						sCS[t][1] = cacheCValues.get(new State(i, tOptTable[j][1]));
				}
				
				if (tOptTable[j][1] >= sCS[t][0] && tOptTable[j][3] != 0)
					nonOptCount++;
				if (tOptTable[j][2] <= sCS[t][1] && tOptTable[j][3] != 0)
					nonOptCount++;
				double maxQ = Math.min(sCS[t][2] - tOptTable[j][1], (tOptTable[j][2] - minCashRequired - fixOrderCost) / variCost);
				maxQ = Math.min(maxQ, maxOrderQ);	
				if (tOptTable[j][1] < sCS[t][0] && tOptTable[j][2] > sCS[t][1] && tOptTable[j][3] != maxQ)
					nonOptCount++;
			}
		}
		System.out.println("there are " + nonOptCount + " states that not satisfy (s, C, S) ordering property");
		return nonOptCount;
	}
	
}
