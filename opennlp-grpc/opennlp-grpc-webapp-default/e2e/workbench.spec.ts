/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.goto("/");
});

test("scopes the hero to the Analyze tab", async ({ page }) => {
  await expect(page.locator("#playground-heading")).toBeVisible();
  await page.click('[data-workbench-tab="corpus-search"]');
  await expect(page.locator("#playground-heading")).toBeHidden();
  await expect(page.locator("#server-search")).toBeVisible();
});

test("bridges the two search tabs in both directions", async ({ page }) => {
  await page.click('[data-workbench-tab="corpus-search"]');
  await page.click('[data-workbench-jump="session-search"]');
  await expect(page.locator("#session-search")).toBeVisible();
  await page.click('[data-workbench-jump="corpus-search"]');
  await expect(page.locator("#server-search")).toBeVisible();
});

test("holds inspector placeholders until a document is selected", async ({ page }) => {
  await page.click('[data-workbench-tab="corpus-search"]');
  for (const counter of await page.locator("#search-analytics dd").all()) {
    await expect(counter).toHaveText("…");
  }
});

test("disables the TSV export with a reason until a vocabulary exists", async ({ page }) => {
  await page.click('[data-workbench-tab="trainer"]');
  const button = page.locator("#trainer-download-tsv-button");
  await expect(button).toBeDisabled();
  await expect(button).toHaveAttribute("title", /vocabulary/);
});
