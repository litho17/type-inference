/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package l8;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Map;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.jobcontrol.Job;
import org.apache.hadoop.mapred.jobcontrol.JobControl;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPublicKey;

import encryption.Util;

public class L8 {

	public static class ReadPageViews extends MapReduceBase implements Mapper<LongWritable, Text, Text, Text> {

		public void map(LongWritable k, Text val, OutputCollector<Text, Text> oc, Reporter reporter)
				throws IOException {

			// Split the line
			List<Text> fields = Library.splitLine(val, '');
			if (fields.size() != 9)
				return;

			StringBuffer sb = new StringBuffer();
			sb.append(fields.get(2).toString());
			sb.append("");
			sb.append(fields.get(6).toString());
			oc.collect(new Text("all"), new Text(sb.toString()));
		}
	}

	public static class Combiner extends MapReduceBase implements Reducer<Text, Text, Text, Text> {

		private PaillierContext context;
		private PaillierPublicKey pub;
		private EncryptedNumber zero;

		@Override
		public void configure(JobConf conf) {
			String pubKey = conf.get("pubKey");
			pub = new PaillierPublicKey(new BigInteger(pubKey));
			context = pub.createSignedContext();
			zero = context.encrypt(0);
		}

		public void reduce(Text key, Iterator<Text> iter, OutputCollector<Text, Text> oc, Reporter reporter)
				throws IOException {
			int erCnt = 0;
			EncryptedNumber tsSum = zero, erSum = zero;
			while (iter.hasNext()) {
				List<Text> vals = Library.splitLine(iter.next(), '');
				erCnt++;
				Text ts = vals.get(0);
				if (ts.getLength() > 0)
					tsSum = context.add(tsSum, Util.getAHCipher(ts.toString(), context));
				if (vals.size() > 1) {
					Text er = vals.get(1);
					if (er.getLength() > 0)
					erSum = context.add(erSum, Util.getAHCipher(er.toString(), context));
				}
			}
			StringBuffer sb = new StringBuffer();
			sb.append(Util.getAHString(tsSum));
			sb.append("");
			sb.append(Util.getAHString(erSum));
			sb.append("");
			sb.append((new Integer(erCnt)).toString());
			oc.collect(key, new Text(sb.toString()));
			reporter.setStatus("OK");
		}
	}

	public static class Group extends MapReduceBase implements Reducer<Text, Text, Text, Text> {

		private PaillierContext context;
		private PaillierPublicKey pub;
		private EncryptedNumber zero;

		@Override
		public void configure(JobConf conf) {
			String pubKey = conf.get("pubKey");
			pub = new PaillierPublicKey(new BigInteger(pubKey));
			context = pub.createSignedContext();
			zero = context.encrypt(0);
		}

		public void reduce(Text key, Iterator<Text> iter, OutputCollector<Text, Text> oc, Reporter reporter)
				throws IOException {
			int erCnt = 0;
			EncryptedNumber tsSum = zero, erSum = zero;
			while (iter.hasNext()) {
				List<Text> vals = Library.splitLine(iter.next(), '');
				Text ts = vals.get(0);
				if (ts.getLength() > 0)
					tsSum = context.add(tsSum, Util.getAHCipher(ts.toString(), context));
				if (vals.size() > 1) {
					Text er = vals.get(1);
					if (er.getLength() > 0)
						erSum = context.add(erSum, Util.getAHCipher(er.toString(), context));
				}
				erCnt += Integer.valueOf(vals.get(2).toString());
			}
			EncryptedNumber erAvg = erSum.divide(erCnt);
			StringBuffer sb = new StringBuffer();
			sb.append(Util.getAHString(tsSum));
			sb.append("");
			sb.append(Util.getAHString(erAvg));
			oc.collect(key, new Text(sb.toString()));
			reporter.setStatus("OK");
		}
	}

	public static void main(String[] args) throws IOException {

		if (args.length != 3) {
			System.out.println("Parameters: inputDir outputDir pubKey");
			System.exit(1);
		}
		String inputDir = args[0];
		String outputDir = args[1];
		JobConf lp = new JobConf(L8.class);
		lp.setJobName("L8 Load Page Views");
		lp.set("pubKey", args[2]);
		lp.setInputFormat(TextInputFormat.class);
		lp.setOutputKeyClass(Text.class);
		lp.setOutputValueClass(Text.class);
		lp.setMapperClass(ReadPageViews.class);
		lp.setCombinerClass(Combiner.class);
		lp.setReducerClass(Group.class);
		Properties props = System.getProperties();
		for (Map.Entry<Object, Object> entry : props.entrySet()) {
			lp.set((String) entry.getKey(), (String) entry.getValue());
		}
		FileInputFormat.addInputPath(lp, new Path(inputDir + "/page_viewsCipher"));
		FileOutputFormat.setOutputPath(lp, new Path(outputDir + "/L8out"));
		lp.setNumReduceTasks(1);
		Job group = new Job(lp);

		JobControl jc = new JobControl("L8 join");
		jc.addJob(group);

		new Thread(jc).start();

		int i = 0;
		while (!jc.allFinished()) {
			ArrayList<Job> failures = jc.getFailedJobs();
			if (failures != null && failures.size() > 0) {
				for (Job failure : failures) {
					System.err.println(failure.getMessage());
				}
				break;
			}

			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
			}

			if (i % 10000 == 0) {
				System.out.println("Running jobs");
				ArrayList<Job> running = jc.getRunningJobs();
				if (running != null && running.size() > 0) {
					for (Job r : running) {
						System.out.println(r.getJobName());
					}
				}
				System.out.println("Ready jobs");
				ArrayList<Job> ready = jc.getReadyJobs();
				if (ready != null && ready.size() > 0) {
					for (Job r : ready) {
						System.out.println(r.getJobName());
					}
				}
				System.out.println("Waiting jobs");
				ArrayList<Job> waiting = jc.getWaitingJobs();
				if (waiting != null && waiting.size() > 0) {
					for (Job r : ready) {
						System.out.println(r.getJobName());
					}
				}
				System.out.println("Successful jobs");
				ArrayList<Job> success = jc.getSuccessfulJobs();
				if (success != null && success.size() > 0) {
					for (Job r : ready) {
						System.out.println(r.getJobName());
					}
				}
			}
			i++;
		}
		ArrayList<Job> failures = jc.getFailedJobs();
		if (failures != null && failures.size() > 0) {
			for (Job failure : failures) {
				System.err.println(failure.getMessage());
			}
		}
		jc.stop();
	}

}
