package us.kbase.common.utils.sortjson.test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import us.kbase.common.utils.sortjson.LowMemoryUTF8JsonSorter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class RandomGenerationLongTest {
	
	public static final boolean PRINT_TIMING = true;	//false;
	public static final boolean USE_MODEL_DATA = false;
	
	@Test
	public void testRandom() throws Exception {
		Random r = new Random(1234567890L);
		File dir = new File("temp_files");
		if (!dir.exists())
			dir.mkdir();
		File tempFile = File.createTempFile("tmp_rnd", ".json", dir);
		int maxSize = 0;
		if (PRINT_TIMING) {
			System.out.println("Test   Size (b) Gen (ms) Jackson (ms) Byte (ms) File (ms) Fast (ms)");
		}
		for (int i = 0; i < 100; i++) {
			long timeGener = System.currentTimeMillis();
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			JsonGenerator jgen = new ObjectMapper().getFactory().createGenerator(baos);
			generateData(r, jgen);
			jgen.close();
			byte[] unsortedJson = baos.toByteArray();
			timeGener = System.currentTimeMillis() - timeGener;
			if (maxSize < unsortedJson.length)
				maxSize = unsortedJson.length;
			long timeJackson = System.currentTimeMillis();
			byte[] expectedJson = sortWithJackson(unsortedJson);
			timeJackson = System.currentTimeMillis() - timeJackson;
			long timeBytes = System.currentTimeMillis();
			byte[] actualJson = sortWithFileSorter(unsortedJson, null);
			timeBytes = System.currentTimeMillis() - timeBytes;
			Assert.assertArrayEquals("i=" + i, expectedJson, actualJson);
			actualJson = null;
			long timeFile = System.currentTimeMillis();
			actualJson = sortWithFileSorter(unsortedJson, tempFile);
			timeFile = System.currentTimeMillis() - timeFile;
			Assert.assertArrayEquals("i=" + i + " (files)", expectedJson, actualJson);
			actualJson = null;
			if (PRINT_TIMING) {
				System.out.print(String.format("%4d ", i));
				System.out.print(String.format("%10d   ", unsortedJson.length));
				System.out.print(String.format("%6d       ", timeGener));
				System.out.print(String.format("%6d    ", timeJackson));
				System.out.print(String.format("%6d    ", timeBytes));
				System.out.print(String.format("%6d ", timeFile));
				System.out.println();
			}
		}
		System.out.println("Max.size: " + maxSize);
	}
	
	private static void generateData(Random r, JsonGenerator jgen) throws Exception {
		if (USE_MODEL_DATA) {
			File f = new File("src/us/kbase/common/performance/sortjson/83333.2.txt");
			JsonNode jn = new ObjectMapper().readTree(f);
			new ObjectMapper().writeValue(jgen, jn);
		} else {
			generateRandomData(r, new int[] {0}, 0, jgen);
		}
	}
	
	private static byte[] sortWithFileSorter(byte[] data, File tempFile) throws Exception {
		LowMemoryUTF8JsonSorter sorter;
		if (tempFile != null) {
			FileOutputStream fos = new FileOutputStream(tempFile);
			fos.write(data);
			fos.close();
			sorter = new LowMemoryUTF8JsonSorter(tempFile);
		} else {
			sorter = new LowMemoryUTF8JsonSorter(data);
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		sorter.writeIntoStream(baos);
		baos.close();
		if (tempFile != null)
			tempFile.delete();
		return baos.toByteArray();
	}

	private static byte[] sortWithJackson(byte[] json) throws Exception {
		ObjectMapper SORT_MAPPER = new ObjectMapper();
		SORT_MAPPER.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		Object d = SORT_MAPPER.readValue(json, Object.class);
		return SORT_MAPPER.writeValueAsBytes(d);
	}
	
	private static void generateRandomData(Random r, int[] largeStringCount, int depth, 
			JsonGenerator out) throws Exception {
		boolean isPrimitive = depth >=4 || r.nextInt(2) == 0;  // On 5th level we choose only primitives
		if (isPrimitive) {
			int typeKind = r.nextInt(4);	
			switch (typeKind) {
			case 0: out.writeNumber(r.nextLong()); break;
			case 1: out.writeNumber(r.nextDouble()); break;
			case 2: {
				if (r.nextInt(3) == 0) {
					out.writeNull();
				} else {
					out.writeBoolean(r.nextInt(2) == 0);
				}
			}
			break;
			case 3: {
				int textLen;
				if (r.nextInt(10) == 0 && largeStringCount[0] < 2) {
					textLen = r.nextInt(5000000);	// length of string is from 0 to 5M in 10% of the cases
					largeStringCount[0]++;
				} else {
					textLen = r.nextInt(1000);	// length of string is from 0 to 999 in 90% cases
				}
				out.writeString(generateRandomString(r, textLen));
			}
			break;
			default: throw new IllegalStateException("Unsupported type code: " + typeKind);
			}
		} else {
			int typeKind = r.nextInt(2);	
			switch (typeKind) {
			case 0: {
				out.writeStartArray();
				int size = r.nextInt(11);	// size of list can be from 0 to 10
				for (int i = 0; i < size; i++)
					generateRandomData(r, largeStringCount, depth + 1, out);
				out.writeEndArray();
			}
			break;
			case 1 : {
				out.writeStartObject();
				int size = r.nextInt(11);	// size of list can be from 0 to 10
				for (int i = 0; i < size; i++) {
					out.writeFieldName(generateRandomString(r, 100));
					generateRandomData(r, largeStringCount, depth + 1, out);
				}
				out.writeEndObject();
			}
			break;
			default: throw new IllegalStateException("Unsupported type code: " + typeKind);
			}
		}
	}

	private static String generateRandomString(Random r, int len) {
		char[] arr = new char[len];
		for (int i = 0; i < len; i++)
			arr[i] = (char)(32 + r.nextInt(127 - 32));
		return new String(arr);
	}
}
