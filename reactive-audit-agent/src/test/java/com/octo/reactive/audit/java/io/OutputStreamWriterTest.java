/*
 * Copyright 2014 OCTO Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.octo.reactive.audit.java.io;

import com.octo.reactive.audit.IOTestTools;
import org.junit.Test;

import java.io.*;

import static com.octo.reactive.audit.TestTools.pop;
import static com.octo.reactive.audit.TestTools.push;

public class OutputStreamWriterTest extends AuditedWriterTest
{
	@Override
	protected Writer newWriter()
			throws IOException
	{
		push();
		OutputStream out = new FileOutputStream(IOTestTools.getTempFile());
		pop();
		return new OutputStreamWriter(out);

	}

	@Test
	public void derived()
			throws IOException
	{
		class Derived extends OutputStreamWriter
		{
			Derived()
			{
				super(new ByteArrayOutputStream(10));
			}
		}
		new Derived();
	}
}
