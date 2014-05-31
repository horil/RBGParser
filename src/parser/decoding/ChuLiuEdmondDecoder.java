package parser.decoding;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.HashMap;

import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;
import parser.DependencyInstance;
import parser.GlobalFeatureData;
import parser.LocalFeatureData;
import parser.Options;
import utils.Utils;

public class ChuLiuEdmondDecoder extends DependencyDecoder {
	
	final int labelLossType;

	TDoubleArrayList lstNumOpt;
    TIntArrayList isOptimal;
    TIntArrayList sentLength;

	public ChuLiuEdmondDecoder(Options options)
	{
		this.options = options;
		this.labelLossType = options.labelLossType;
		lstNumOpt = new TDoubleArrayList();
		isOptimal = new TIntArrayList();
		sentLength = new TIntArrayList();
	}
	
	private static boolean print = false;
    
	public void printLocalOptStats()
	{
        System.out.printf("\tsize=%d%n", lstNumOpt.size());
		lstNumOpt.sort();
		int N = lstNumOpt.size();
		for (int i = 1; i < 10; i += 2)
			System.out.printf("\t%.0f", lstNumOpt.get((int) (N*0.1*i)));
		System.out.println();
	}
	
    public void printLocalOptStats2()
    {
            Utils.Assert(lstNumOpt.size() == isOptimal.size());
            Utils.Assert(sentLength.size() == isOptimal.size());
            for (int i = 0; i < lstNumOpt.size(); ++i) {
                    if (!(lstNumOpt.get(i) >= 0)) {
                            System.out.println(lstNumOpt.get(i));
                    }
            }
            // sort
            for (int i = 0; i < lstNumOpt.size(); ++i) {
            	for (int j = i + 1; j < lstNumOpt.size(); ++j) {
            		if (lstNumOpt.get(i) > lstNumOpt.get(j)) {
            			double tmp = lstNumOpt.get(i);
            			lstNumOpt.set(i, lstNumOpt.get(j));
            			lstNumOpt.set(j, tmp);
            			int tmpi = isOptimal.get(i);
            			isOptimal.set(i, isOptimal.get(j));
            			isOptimal.set(j, tmpi);
            			tmpi = sentLength.get(i);
            			sentLength.set(i, sentLength.get(j));
            			sentLength.set(j, tmpi);
            		}
            	}
            }

            /*
            int N = 10;
            for (int i = 0; i < N; i++) {
            	int tot = 0;
            	double isOpt = 0.0;
            	double numOpt = 0.0;
            	double len = 0.0;

            	int st = lstNumOpt.size() / N * i;
            	int en = lstNumOpt.size() / N * (i + 1);
            	if (i == N - 1)
            		en = lstNumOpt.size();

            	for (int j = st; j < en; j++) {
            		if (lstNumOpt.get(j) < 0)
            			continue;
            		tot++;
            		isOpt += isOptimal.get(j);
            		numOpt += lstNumOpt.get(j);
            		len += sentLength.get(j);
            	}
            	System.out.printf("\t%.3f/%.3f/%.3f", isOpt/tot, numOpt/tot, len / tot);
            }
            System.out.println();
            */
            
            try {
            	BufferedWriter bw = new BufferedWriter(new FileWriter("debug." + Options.langString[options.lang.ordinal()]));
            	for (int i = 0; i < lstNumOpt.size(); ++i) {
            		bw.write("" + sentLength.get(i) + "\t" + lstNumOpt.get(i) + "\t" + isOptimal.get(i) + "\n");
            	}
            	bw.close();
            } catch (Exception e) {
            	e.printStackTrace();
            }
    }

    @Override
	public DependencyInstance decode(DependencyInstance inst,
			LocalFeatureData lfd, GlobalFeatureData gfd, boolean addLoss) 
	{
		int N = inst.length;
        int M = N << 1;
        
        int[] deps = inst.heads;
        int[] labs = inst.deplbids;
        int[][] staticTypes = null;
		if (options.learnLabel)
		    staticTypes = lfd.getStaticTypes();
        
        double[][] scores = new double[M][M];
        int[][] oldI = new int[M][M];
        int[][] oldO = new int[M][M];
        for (int i = 0; i < N; ++i)
            for (int j = 1; j < N; ++j) 
                if (i != j) {
                    oldI[i][j] = i;
                    oldO[i][j] = j;
                    if (lfd.hasPruning() && lfd.isPruned(i,j)) {
                        //scores[i][j] = -1e+50;
                    	scores[i][j] = Double.NEGATIVE_INFINITY;
                        continue;
                    }
                    double va = lfd.getArcScore(i,j);
                    if (options.learnLabel) {
                        int t = staticTypes[i][j];
                        va += lfd.getLabeledArcScore(i,j,t);
                        if (addLoss) {
                        	if (labelLossType == 0) {
                        		if (labs[j] != t) va += 0.5;
                        		if (deps[j] != i) va += 0.5;
                        	} else if (labs[j] != t || deps[j] != i) va += 1.0;
                        }                                            
                    } 
                    else if (addLoss && deps[j] != i) va += 1.0;                    
                    scores[i][j] = va;
                }

        boolean[] ok = new boolean[M];
        boolean[] vis = new boolean[M];
        boolean[] stack = new boolean[M];
        for (int i = 0; i < M; ++i) ok[i] = true;

        int[] final_par = new int[M];
        for (int i = 0; i < M; ++i) final_par[i] = -1;
        
        double appoxNumLocalOpt = chuLiuEdmond(N, scores, ok, vis, stack, oldI, oldO, final_par);
        
        if (print) System.out.println();
        
		DependencyInstance predInst = new DependencyInstance(inst);
		predInst.heads = new int[N];
		predInst.deplbids = new int[N];
	    
        predInst.heads[0] = -1;
        for (int i = 1; i < N; ++i) {
            int j = final_par[i];
            int t = options.learnLabel ? staticTypes[j][i] : 0;
            predInst.heads[i] = j;
            predInst.deplbids[i] = t;
        }
        
        for (int i = 0; i < M; ++i) ok[i] = true;
        double numLocalOpt = chuLiuEdmond2(N, scores, ok, vis, stack, oldI, oldO, final_par);
        //double numLocalOpt = 1.0;
        lstNumOpt.add(numLocalOpt);
        sentLength.add(inst.length - 1);
        //System.out.println(appoxNumLocalOpt + " " + numLocalOpt);
        
        return predInst;
	}
	
	public double chuLiuEdmond(int N, double[][] scores, boolean[] ok, boolean[] vis,
            boolean[] stack, int[][] oldI, int[][] oldO, int[] final_par) {

        // find best graph
        int[] par = new int[N];
        par[0] = -1;
        for (int i = 0; i < N; ++i) par[i] = -1;
        for (int i = 1; i < N; ++i) if (ok[i]) {
            par[i] = 0;
            double max = scores[0][i];
            for (int j = 1; j < N; ++j) 
                if (i != j && ok[j] && max < scores[j][i]) {
                    par[i] = j;
                    max = scores[j][i]; 
                }
            //DEBUG
            Utils.Assert(max != Double.NEGATIVE_INFINITY);
        }

        // find the longest circle
        int maxLen = 0;
        int start = -1;
        for (int i = 0; i < N; ++i) vis[i] = false;
        for (int i = 0; i < N; ++i) stack[i] = false;
        for (int i = 0; i < N; ++i) {
            // if this is not a valid node or
            // it is already visited
            if (vis[i] || !ok[i]) continue;
            int j = i;
            while (j != -1 && !vis[j]) {
                vis[j] = true;
                stack[j] = true;
                j = par[j];
            }

            if (j != -1 && stack[j]) {
                // find a circle j --> ... --> j
                int size = 1, k = par[j];
                while (k != j) {
                    k = par[k];
                    ++size;
                }
                // keep the longest circle
                if (size > maxLen) {
                    maxLen = size;
                    start = j;
                }
            }

            // clear stack
            j = i;
            while (j != -1 && stack[j]) {
                stack[j] = false;
                j = par[j];
            }
        }
        
        // if there's no circle, return the result tree
        if (maxLen == 0) {
            for (int i = 0; i < N; ++i) final_par[i] = par[i];
            if (print) {
                System.out.printf("Tree: ");
                for (int i = 0; i < N; ++i) if (final_par[i] != -1)
                    System.out.printf("%d-->%d ", final_par[i], i);
                System.out.println();
            }
            return 1.0;
        }
        
        if (print) {
            System.out.printf("Circle: ");
            for (int i = start; ;) {
                System.out.printf("%d<--", i);
                i = par[i];
                if (i == start) break;
            }
            System.out.println(start);
        }

        // otherwise, contract the circle 
        // and add a virtual node v_N
         
        // get circle cost and mark all nodes on the circle
        double circleCost = scores[par[start]][start];
        stack[start] = true;
        ok[start] = false;
        for (int i = par[start]; i != start; i = par[i]) {
            stack[i] = true;
            ok[i] = false;
            circleCost += scores[par[i]][i];
        }
        
        for (int i = 0; i < N; ++i) {
            if (stack[i] || !ok[i]) continue;
            
            double maxToCircle = Double.NEGATIVE_INFINITY;
            double maxFromCircle = Double.NEGATIVE_INFINITY;
            int toCircle = -1;
            int fromCircle = -1;

            for (int j = start; ;) {
                if (scores[j][i] > maxFromCircle) {
                    maxFromCircle = scores[j][i];
                    fromCircle = j;
                }
                double newScore = circleCost + scores[i][j] - scores[par[j]][j];
                if (newScore > maxToCircle) {
                    maxToCircle = newScore;
                    toCircle = j;
                }
                j = par[j];
                if (j == start) break;
            }

            scores[N][i] = maxFromCircle;
            oldI[N][i] = fromCircle;;
            oldO[N][i] = i;
            scores[i][N] = maxToCircle;
            oldI[i][N] = i;
            oldO[i][N] = toCircle;
        }

        double numLocalOpt = chuLiuEdmond(N+1, scores, ok, vis, stack, oldI, oldO, final_par);

        // construct tree from contracted one
        for (int i = 0; i < N; ++i) 
            if (final_par[i] == N) final_par[i] = oldI[N][i];
        final_par[oldO[final_par[N]][N]] = final_par[N];
        for (int i = start; ;) {
            int j = par[i];
            // j --> i
            if (final_par[i] == -1) final_par[i] = j;
            i = j;
            if (i == start) break;
        }

        if (print) {
            System.out.printf("Tree: ");
            for (int i = 0; i < N; ++i) if (final_par[i] != -1)
                System.out.printf("%d-->%d ", final_par[i], i);
            System.out.println();
        }
        
        //System.out.printf(" %d", maxLen);
        return numLocalOpt * maxLen;

    }
	
	
	static int MAXNUM = 10000;
	public double chuLiuEdmond2(int N, double[][] scores, boolean[] ok, boolean[] vis,
            boolean[] stack, int[][] oldI, int[][] oldO, int[] final_par) {
		
		double tot = 0;
						
        // find best graph
        int[] par = new int[N];
        par[0] = -1;
        for (int i = 0; i < N; ++i) par[i] = -1;
        for (int i = 1; i < N; ++i) if (ok[i]) {
            par[i] = 0;
            double max = scores[0][i];
            for (int j = 1; j < N; ++j) 
                if (i != j && ok[j] && max < scores[j][i]) {
                    par[i] = j;
                    max = scores[j][i]; 
                }
            if (max == Double.NEGATIVE_INFINITY) {
            	//System.out.println("!!!");
            	return 0;
            }
        }

        // find the longest circle
        int maxLen = 0;
        int start = -1;
        for (int i = 0; i < N; ++i) vis[i] = false;
        for (int i = 0; i < N; ++i) stack[i] = false;
        for (int i = 0; i < N; ++i) {
            // if this is not a valid node or
            // it is already visited
            if (vis[i] || !ok[i]) continue;
            int j = i;
            while (j != -1 && !vis[j]) {
                vis[j] = true;
                stack[j] = true;
                j = par[j];
            }

            if (j != -1 && stack[j]) {
                // find a circle j --> ... --> j
                int size = 1, k = par[j];
                while (k != j) {
                    k = par[k];
                    ++size;
                }
                // keep the longest circle
                if (size > maxLen) {
                    maxLen = size;
                    start = j;
                }
            }

            // clear stack
            j = i;
            while (j != -1 && stack[j]) {
                stack[j] = false;
                j = par[j];
            }
        }
        
        // if there's no circle, return the result tree
        if (maxLen == 0) {
            for (int i = 0; i < N; ++i) final_par[i] = par[i];
            if (print) {
                System.out.printf("Tree: ");
                for (int i = 0; i < N; ++i) if (final_par[i] != -1)
                    System.out.printf("%d-->%d ", final_par[i], i);
                System.out.println();
            }
            return 1.0;
        }
        
        if (print) {
            System.out.printf("Circle: ");
            for (int i = start; ;) {
                System.out.printf("%d<--", i);
                i = par[i];
                if (i == start) break;
            }
            System.out.println(start);
        }

        // otherwise, contract the circle 
        // and add a virtual node v_N
         
        // get circle cost and mark all nodes on the circle
        double circleCost = scores[par[start]][start];
        stack[start] = true;
        ok[start] = false;
        for (int i = par[start]; i != start; i = par[i]) {
            stack[i] = true;
            ok[i] = false;
            circleCost += scores[par[i]][i];
        }
        
		int num = 0;
		for (int i = 1; i <= N; ++i) 
			if (ok[i]) ++num;
		//System.out.println("num: " + num + " " + N + " " + maxLen);
		Utils.Assert(num >= 1);
		
		boolean[] ok2 = new boolean[ok.length];
		for (int i = 0; i < ok2.length; ++i) ok2[i] = ok[i];
	
        for (int i = 0; i < N; ++i) {
            if (stack[i] || !ok[i]) continue;
            
            //double maxToCircle = Double.NEGATIVE_INFINITY;
            double maxFromCircle = Double.NEGATIVE_INFINITY;
            //int toCircle = -1;
            int fromCircle = -1;

            for (int j = start; ;) {
                if (scores[j][i] > maxFromCircle) {
                    maxFromCircle = scores[j][i];
                    fromCircle = j;
                }
                //double newScore = circleCost + scores[i][j] - scores[par[j]][j];
                //if (newScore > maxToCircle) {
                //    maxToCircle = newScore;
                //    toCircle = j;
                //}
                j = par[j];
                if (j == start) break;
            }

            scores[N][i] = maxFromCircle;
            //oldI[N][i] = fromCircle;;
            //oldO[N][i] = i;
            //scores[i][N] = maxToCircle;
            //oldI[i][N] = i;
            //oldO[i][N] = toCircle;
        }
        
        for (int node = start; ;) {
        	
        	if (tot >= MAXNUM) {
        		long x = (1L << (num-1));
        		tot += x > 0 ? x : Math.pow(2, num-1);
        	} else {
	        	for (int i = 0; i < ok2.length; ++i) ok[i] = ok2[i];
	        	for (int i = 0; i < N; ++i) {
	        		if (stack[i] || !ok[i]) continue;
	        		scores[i][N] = scores[i][node];
	        		//oldI[i][N] = i;
	        		//oldO[i][N] = node;
	        	}
	        	tot += chuLiuEdmond2(N+1, scores, ok, vis, stack, oldI, oldO, final_par);
        	}
        	node = par[node];
        	if (node == start) break;
        }


//        // construct tree from contracted one
//        for (int i = 0; i < N; ++i) 
//            if (final_par[i] == N) final_par[i] = oldI[N][i];
//        final_par[oldO[final_par[N]][N]] = final_par[N];
//        for (int i = start; ;) {
//            int j = par[i];
//            // j --> i
//            if (final_par[i] == -1) final_par[i] = j;
//            i = j;
//            if (i == start) break;
//        }

        if (print) {
            System.out.printf("Tree: ");
            for (int i = 0; i < N; ++i) if (final_par[i] != -1)
                System.out.printf("%d-->%d ", final_par[i], i);
            System.out.println();
        }
        
        //System.out.printf(" %d", maxLen);
        //return numLocalOpt * maxLen;
        return tot;
    }
	
	public DependencyInstance majorityVote(DependencyInstance inst, HashMap<Integer, Integer> arcCount) 
	{
		int N = inst.length;
        int M = N << 1;
        
        double[][] scores = new double[M][M];
        int[][] oldI = new int[M][M];
        int[][] oldO = new int[M][M];
        for (int i = 0; i < N; ++i)
            for (int j = 1; j < N; ++j) 
                if (i != j) {
                    oldI[i][j] = i;
                    oldO[i][j] = j;
                    
                    int code = i * inst.length + j;
                    if (!arcCount.containsKey(code)) {
                    	scores[i][j] = Double.NEGATIVE_INFINITY;
                    	//scores[i][j] = 0;
                        continue;
                    }
                    else {
	                    double va = arcCount.get(code);
	                    scores[i][j] = va;
                    }
                }

        boolean[] ok = new boolean[M];
        boolean[] vis = new boolean[M];
        boolean[] stack = new boolean[M];
        for (int i = 0; i < M; ++i) ok[i] = true;

        int[] final_par = new int[M];
        for (int i = 0; i < M; ++i) final_par[i] = -1;
        
        chuLiuEdmond(N, scores, ok, vis, stack, oldI, oldO, final_par);
        
        if (print) System.out.println();
        
		DependencyInstance predInst = new DependencyInstance(inst);
		predInst.heads = new int[N];
		predInst.deplbids = new int[N];
	    
        predInst.heads[0] = -1;
        for (int i = 1; i < N; ++i) {
            int j = final_par[i];
            int t = 0;
            predInst.heads[i] = j;
            predInst.deplbids[i] = t;
        }
        
        return predInst;
	}
	
}
